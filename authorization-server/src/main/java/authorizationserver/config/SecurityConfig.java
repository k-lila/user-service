package authorizationserver.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import authorizationserver.filter.AuthorizationEndpointRevalidationFilter;
import authorizationserver.services.RevocationRefreshGuard;

@Configuration
@EnableWebSecurity
// Sessão HTTP (login/consent) no Redis — escala horizontal.
// Habilitação explícita: no Spring Boot 4.0 a autoconfig de Spring Session não dispara só pela dep.
// redisNamespace dedicado ("authserver:session"): isola as sessões do auth-server das do gateway
// ("gateway:session") no mesmo Redis, em vez de depender só da unicidade dos session ids.
@EnableRedisHttpSession(redisNamespace = "authserver:session")
public class SecurityConfig {

	@Value("${auth.issuer}")
	private String issuer;

	// Flag Secure do cookie AUTHSESSION. Default false p/ dev HTTP puro; o overlay de deploy
	// (docker-compose.deploy.yml, Cloudflare) liga via APP_COOKIE_SECURE=true. Simétrico ao
	// gateway: atrás de proxy que termina TLS a flag é explícita (não inferida do request).
	@Value("${app.cookie.secure:false}")
	private boolean cookieSecure;

	// Teto de vida da sessão do IdP, medido a partir do INSTANTE DE AUTENTICAÇÃO (ADR-025) — não de
	// getCreationTime(), que no BFF mede também o tempo de sessão anônima (CSRF + saved request) e
	// faria um login legítimo nascer vencido. Os 30 min do Spring Session são de INATIVIDADE e cada
	// autorização os renova: sem este teto, uma sessão em uso contínuo nunca expira.
	// GRANULARIDADE REAL ≈ vida do refresh token (60 min, defaults do SAS): quem renova por
	// refresh_token não passa pelo authorize. "Teto absoluto" seria a mesma imprecisão de "revogação
	// força re-autenticação" que a ADR-025 corrige.
	@Value("${security.session.max-lifetime:8h}")
	private Duration sessionMaxLifetime;

	// REVERSÃO OPERACIONAL, não configuração suportada (ADR-025). O raio de um defeito no filtro de
	// re-derivação é "ninguém consegue logar" (R-01, P0), e com a propriedade servida pelo
	// config-server o rollback é restart, sem rebuild. Precedente: TOKEN_REVOCATION_ENABLED.
	// Em `false` o sistema volta ao comportamento vulnerável descrito na ADR-025 — não deixe assim.
	@Value("${security.session.revalidation.enabled:true}")
	private boolean revalidationEnabled;

	// Cookie de sessão com nome próprio (AUTHSESSION) para NÃO colidir com o cookie
	// SESSION do gateway: gateway e auth-server compartilham o host "localhost" em dev
	// (cookies ignoram a porta), e o default do Spring Session ("SESSION") nos dois faria
	// o cookie do auth-server sobrescrever o do gateway, quebrando o callback OAuth2.
	@Bean
	public CookieSerializer cookieSerializer() {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("AUTHSESSION");
		serializer.setCookiePath("/");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(cookieSecure);
		serializer.setSameSite("Lax");
		return serializer;
	}

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerSecurityFilterChain(
			HttpSecurity http,
			AuthorizationServerSettings authorizationServerSettings,
			UserDetailsService userDetailsService,
			RevocationRefreshGuard revocationGuard)
			throws Exception {
		http
			.oauth2AuthorizationServer((authorizationServer) -> {
				http
					.securityMatcher(authorizationServer.getEndpointsMatcher())
					// CORS no endpoint de token (C12): o Swagger-UI troca o code pelo token
					// via fetch cross-origin (:8081 → :8082/oauth2/token). Origens configuráveis
					// em CORSConfig (cors.allowed-origins).
					.cors(Customizer.withDefaults());
				authorizationServer
					.oidc(Customizer.withDefaults());
			})
			.authorizeHttpRequests((authorize) ->
				authorize
					.anyRequest().authenticated()
			)
			.exceptionHandling((exceptions) -> exceptions
				.defaultAuthenticationEntryPointFor(
					new LoginUrlAuthenticationEntryPoint("/login"),
					new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
				)
			);

		// Re-derivação do titular na emissão (ADR-025). TRÊS coisas a NÃO regredir aqui:
		//
		//  (1) A POSIÇÃO. addFilterAfter(SecurityContextHolderFilter): antes dele o
		//      SecurityContextHolder ainda está vazio e o filtro vira NO-OP SILENCIOSO — filtro
		//      correto em posição errada é a mesma classe de falha do sampling de tracing
		//      documentado e inerte, com build verde. Guarda:
		//      AuthorizationChainStructureIntegrationTest assere o índice na FilterChainProxy.
		//  (2) A CHAIN. Só a @Order(1) serve: é ela que tem securityMatcher(endpointsMatcher) e,
		//      portanto, a única que vê /oauth2/authorize. Na @Order(2) o filtro nunca rodaria.
		//  (3) NÃO é @Component nem @Bean. Qualquer bean de tipo Filter é auto-registrado pelo
		//      Boot no container servlet, o que o faria atuar em TODO path fora da security chain
		//      — inclusive na porta de management. Instanciar com `new` aqui é o que mantém o
		//      escopo restrito ao matcher positivo do próprio filtro.
		http.addFilterAfter(
			new AuthorizationEndpointRevalidationFilter(
				// Derivado das settings, nunca do literal "/oauth2/authorize": um endpoint
				// customizado faria o literal parar de casar em silêncio.
				authorizationServerSettings.getAuthorizationEndpoint(),
				userDetailsService,
				revocationGuard,
				sessionMaxLifetime,
				revalidationEnabled),
			SecurityContextHolderFilter.class);

		return http.build();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http)
			throws Exception {
		http
			.cors(Customizer.withDefaults())
			.authorizeHttpRequests((authorize) -> authorize
				.requestMatchers(
					        "/oauth2/**",
        					"/.well-known/**",
        					"/login",
        					"/error",
					// NÃO remova "/actuator/**" achando que é resíduo depois que o actuator foi
					// para a porta de management (8181, gap G14): esta chain governa TAMBÉM essa
					// porta — o contexto filho de management herda o filtro de segurança do pai.
					// Sem esta linha o formLogin redireciona o actuator da 8181 para /login (302),
					// o Prometheus recebe HTML e para de raspar — e o healthcheck do compose passa
					// mesmo assim, porque `curl -f` não falha em 302 (falso healthy medido em
					// 2026-08-05). O controle do G14 é a 8181 não ser publicada, não este matcher.
							"/actuator/**"
				).permitAll()
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				.anyRequest().authenticated()
			)
			.formLogin(Customizer.withDefaults());

		return http.build();
	}

	@Bean
	public AuthorizationServerSettings authorizationServerSettings() {
		return AuthorizationServerSettings
			.builder()
			.issuer(issuer)
			.build();
	}

}
