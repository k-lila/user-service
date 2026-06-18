package authorizationserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

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
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
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
