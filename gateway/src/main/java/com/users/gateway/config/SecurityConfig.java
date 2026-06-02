package com.users.gateway.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            ServerLogoutSuccessHandler oidcLogoutSuccessHandler) {
        http
            // CSRF por token sincronizador: cookie XSRF-TOKEN legível pelo SPA (BFF + sessão).
            // Handler "plano" (não-XOR) para o token bruto do cookie casar com o header X-XSRF-TOKEN.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                // /users/register é público e pré-sessão: CSRF não protege nada ali.
                .requireCsrfProtectionMatcher(new AndServerWebExchangeMatcher(
                        CsrfWebFilter.DEFAULT_CSRF_MATCHER,
                        new NegatedServerWebExchangeMatcher(
                                ServerWebExchangeMatchers.pathMatchers("/users/register"))
                ))
            )
            .cors(Customizer.withDefaults())
            .authorizeExchange(exchange -> exchange
                .pathMatchers(
                        "/login",
                        "/oauth2/**",
                        "/oauth2/token",
                        "/login/oauth2/**",
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/users/register"
                ).permitAll()
                .anyExchange().authenticated()
            )
            .oauth2Login(Customizer.withDefaults())
            .oauth2Client(Customizer.withDefaults())
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(Customizer.withDefaults())
            )
            // BFF: requisição de API não autenticada devolve 401 (o SPA decide o login),
            // em vez de redirecionar a chamada XHR para o /oauth2/authorize.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            // RP-Initiated Logout: POST /logout encerra a sessão e redireciona ao end_session do IdP.
            .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler));
        return http.build();
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