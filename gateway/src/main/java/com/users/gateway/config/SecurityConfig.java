package com.users.gateway.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint.DelegateEntry;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
// Sessão WebFlux (OAuth2AuthorizedClient/JWT) no Redis — escala horizontal.
// Habilitação explícita: no Spring Boot 4.0 a autoconfig de Spring Session não dispara só pela dep.
// redisNamespace dedicado ("gateway:session"): isola as sessões do gateway das do auth-server
// ("authserver:session") no mesmo Redis, em vez de depender só da unicidade dos session ids.
@EnableRedisWebSession(redisNamespace = "gateway:session")
public class SecurityConfig {

    // Flag Secure dos cookies (SESSION/XSRF-TOKEN). Default false p/ dev HTTP puro;
    // o overlay de deploy (docker-compose.deploy.yml, Cloudflare) liga via APP_COOKIE_SECURE=true.
    // Atrás de proxy que termina TLS o sslInfo do exchange é null, então a flag é explícita (não inferida).
    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            ServerLogoutSuccessHandler oidcLogoutSuccessHandler,
            ReactiveClientRegistrationRepository clientRegistrationRepository) {
        CookieServerCsrfTokenRepository csrfTokenRepository =
                CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie.secure(cookieSecure));
        http
            // CSRF por token sincronizador: cookie XSRF-TOKEN legível pelo SPA (BFF + sessão).
            // Handler "plano" (não-XOR) para o token bruto do cookie casar com o header X-XSRF-TOKEN.
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                // /v1/users/register (POST) é público e pré-sessão (ADR-015): CSRF não protege
                // nada ali (sem cookie/sessão para um atacante forjar). /v1/users/verify-email
                // é GET — já fora do CSRF por padrão. /v1/users/resend-verification deixou de
                // ser pré-sessão (agora self-service autenticado) — exige X-XSRF-TOKEN como
                // qualquer outra rota autenticada.
                //
                // /login (POST) é isentado pelo mesmo racional de /v1/users/register: o request
                // é unauthenticated (sem sessão do gateway que um atacante possa forjar).
                // A proteção CSRF do gateway defende ações sobre a sessão do gateway; POST /login
                // não tem sessão a defender. O auth-server tem CSRF próprio no formulário que
                // ele renderiza — o gateway não tem como embutir seu XSRF-TOKEN num HTML gerado
                // pelo auth-server.
                // Escopo: pathMatchers("/login") compara contra getPath().pathWithinApplication()
                // (sem query string), portanto casa /login E /login?error — mas /login?error é
                // GET e já fica fora do DEFAULT_CSRF_MATCHER por método seguro. Não casa
                // /login/oauth2/code/gateway-client (path diferente). (ADR-019, BUG-001/003)
                .requireCsrfProtectionMatcher(new AndServerWebExchangeMatcher(
                        CsrfWebFilter.DEFAULT_CSRF_MATCHER,
                        new NegatedServerWebExchangeMatcher(
                                ServerWebExchangeMatchers.pathMatchers(
                                        "/v1/users/register",
                                        "/login"))
                ))
            )
            .cors(Customizer.withDefaults())
            .authorizeExchange(exchange -> exchange
                .pathMatchers(
                        "/login",
                        "/oauth2/**",
                        "/oauth2/token",
                        "/login/oauth2/**",
                        // Front-channel do RP-Initiated Logout (ADR-018): o browser chega aqui
                        // DEPOIS de o POST /logout ter encerrado a sessão do gateway, ou seja,
                        // sem autenticação. Fora do permitAll, o entry point devolveria 401 e o
                        // logout nunca alcançaria o end_session_endpoint do auth-server.
                        "/connect/**",
                        // CSS do formulário de login do IdP (ADR-019): solicitado pelo browser
                        // antes de autenticar (sem sessão). Sem permitAll, 401 → formulário sem
                        // estilo. Roteado pela rota auth-default-ui → lb://authorization-server.
                        "/default-ui.css",
                        // NÃO é resíduo, apesar de o actuator já viver na porta de management
                        // 8181 (gateway.yml): esta chain governa TAMBÉM essa porta. Remover a
                        // linha faz /actuator/health e /actuator/prometheus na 8181 devolverem
                        // 401 — healthcheck do compose e scrape do Prometheus caem junto
                        // (medido no user-service em 2026-08-05, ao fechar o G14). O controle
                        // é a 8181 não ser publicada, não este matcher.
                        "/actuator/**",
                        // /swagger-ui/**, /swagger-ui.html e /v3/api-docs/** SAÍRAM do permitAll
                        // (ADR-020): caem em anyExchange().authenticated(). O Cloudflare Access,
                        // controle previsto para eles, exige cartão no Zero Trust — a sessão OAuth2
                        // do próprio BFF passou a ser o controle de acesso da documentação.
                        "/v1/users/register",
                        "/v1/users/verify-email",
                        "/v1/users/resend-verification"
                ).permitAll()
                .anyExchange().authenticated()
            )
            // PKCE no oauth2Login: o gateway-client exige proof key (requireProofKey(true) no
            // authorization-server). Sem isso, o cliente confidencial não envia code_challenge e
            // o auth-server rejeita a autorização com invalid_request, quebrando o fluxo BFF.
            .oauth2Login(oauth2 -> oauth2
                .authorizationRequestResolver(pkceAuthorizationRequestResolver(clientRegistrationRepository)))
            .oauth2Client(Customizer.withDefaults())
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(Customizer.withDefaults())
            )
            // BFF: requisição de API não autenticada devolve 401 (o SPA decide o login),
            // em vez de redirecionar a chamada XHR para o /oauth2/authorize.
            // Exceção: /swagger-ui/** é NAVEGAÇÃO de browser, não XHR — ver swaggerAwareEntryPoint().
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(swaggerAwareEntryPoint())
            )
            // RP-Initiated Logout: POST /logout encerra a sessão e redireciona ao end_session do IdP.
            .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler));
        return http.build();
    }

    // Entry point híbrido (ADR-020). O default do BFF é 401: o SPA é cliente JSON e decide sozinho
    // quando iniciar o login — redirecionar um XHR para o /oauth2/authorize devolveria HTML onde o
    // front espera JSON. Mas /swagger-ui/** deixou de ser público e passou a ser navegação de
    // browser: com 401 seco o operador veria uma página em branco, sem caminho para autenticar.
    // Só esses paths ganham 302 para o oauth2Login; todo o resto continua 401.
    //
    // Volta ao Swagger depois do login: RedirectServerAuthenticationEntryPoint já traz um
    // WebSessionServerRequestCache por default e o OAuth2LoginSpec injeta o cache do
    // ServerHttpSecurity no seu success handler — instâncias distintas, mesma chave de atributo na
    // WebSession, então o request salvo é restaurado.
    //
    // /v3/api-docs/** fica FORA desta lista de propósito: é caminho de XHR (o JS da página busca o
    // doc). Um 302 para o HTML do login faria o swagger-client tentar parsear a tela de login como
    // JSON; o 401 do default é o erro legível. O caminho normal — carregar a página primeiro — já
    // garante a sessão antes de o doc ser buscado.
    private ServerAuthenticationEntryPoint swaggerAwareEntryPoint() {
        RedirectServerAuthenticationEntryPoint swaggerLogin =
                new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/gateway-client");

        DelegatingServerAuthenticationEntryPoint entryPoint =
                new DelegatingServerAuthenticationEntryPoint(new DelegateEntry(
                        ServerWebExchangeMatchers.pathMatchers("/swagger-ui/**", "/swagger-ui.html"),
                        swaggerLogin));
        entryPoint.setDefaultEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED));
        return entryPoint;
    }

    // Resolver de authorization request com PKCE habilitado: adiciona code_challenge/code_challenge_method
    // (S256) na ida ao authorization-server e guarda o code_verifier para a troca de código.
    private ServerOAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(
            ReactiveClientRegistrationRepository clientRegistrationRepository) {
        DefaultServerOAuth2AuthorizationRequestResolver resolver =
                new DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrationRepository);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    // Após o logout local, redireciona o browser ao end_session do auth-server (encerra a sessão
    // do IdP). A URL é browser-reachable (localhost:8082, não o host interno da discovery) — o
    // id_token_hint vem da sessão OIDC e dispensa a tela de confirmação do auth-server.
    @Bean
    public ServerLogoutSuccessHandler oidcLogoutSuccessHandler(
            @Value("${OAUTH_END_SESSION_URI:http://localhost:8082/connect/logout}") String endSessionUri,
            @Value("${POST_LOGOUT_REDIRECT_URI:http://localhost:5173/}") String postLogoutRedirectUri) {
        ServerRedirectStrategy redirectStrategy = new DefaultServerRedirectStrategy();
        return (exchange, authentication) -> {
            String idTokenHint = (authentication != null
                    && authentication.getPrincipal() instanceof OidcUser oidcUser)
                    ? oidcUser.getIdToken().getTokenValue()
                    : "";
            URI location = UriComponentsBuilder.fromUriString(endSessionUri)
                    .queryParam("id_token_hint", idTokenHint)
                    .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                    .build()
                    .toUri();
            return redirectStrategy.sendRedirect(exchange.getExchange(), location);
        };
    }

    // Cookie SESSION (Spring Session reativo): o WebSessionManager do Spring Session
    // adota este resolver (autowire opcional). Default cookieName="SESSION", HttpOnly e
    // SameSite=Lax; aqui só fixamos a flag Secure conforme app.cookie.secure.
    @Bean
    public WebSessionIdResolver webSessionIdResolver() {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.addCookieInitializer(cookie -> cookie
                .httpOnly(true)
                .sameSite("Lax")
                .secure(cookieSecure));
        return resolver;
    }

    // O CsrfToken é carregado de forma lazy no WebFlux; subscrevê-lo a cada request
    // garante que o cookie XSRF-TOKEN seja efetivamente escrito na resposta.
    @Bean
    public WebFilter csrfCookieWebFilter() {
        return (exchange, chain) -> {
            Mono<CsrfToken> csrfToken = exchange.getAttribute(CsrfToken.class.getName());
            return csrfToken != null
                    ? csrfToken.then(chain.filter(exchange))
                    : chain.filter(exchange);
        };
    }

}