package authorizationserver.config;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.jdbc.core.JdbcTemplate;


@Configuration
public class OAuth2ClientConfig {

    @Value("${oauth.client.secret}")
    private String clientSecret;

    @Bean
	PasswordEncoder passwordEncoder() {
	    return new BCryptPasswordEncoder();
	}

    // Estado OAuth persistido em JDBC (Postgres) para suportar N instâncias: o `code`
    // emitido por uma instância precisa ser trocável por token em qualquer outra.
    // Substitui InMemoryRegisteredClientRepository + os defaults InMemory de
    // OAuth2AuthorizationService/OAuth2AuthorizationConsentService.
    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcTemplate);
        // Seeding idempotente do gateway-client (antes em memória). Preserva todos os
        // redirectUri/scopes — incluindo o redirect do Swagger UI.
        if (repository.findByClientId("gateway-client") == null) {
            repository.save(gatewayClient());
        }
        return repository;
    }

    // Nome do bean distinto de 'authorizationService' (o UserDetailsService
    // authorizationserver.services.AuthorizationService já ocupa esse nome). Wiring é por tipo.
    @Bean
    public OAuth2AuthorizationService oauth2AuthorizationService(JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public OAuth2AuthorizationConsentService oauth2AuthorizationConsentService(JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    private RegisteredClient gatewayClient() {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("gateway-client")
                .clientSecret(passwordEncoder().encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:8081/login/oauth2/code/gateway-client")
                .redirectUri("http://localhost:5173/login/oauth2/code/gateway-client") // SPA (BFF): callback via proxy do front (Vite :5173 dev / nginx :5173 Docker)
                .redirectUri("https://app.localhost/login/oauth2/code/gateway-client") // borda TLS de dev (mkcert): callback via tls-proxy (docker-compose.tls.yml)
                .redirectUri("http://localhost:8081/swagger-ui/oauth2-redirect.html")
                .redirectUri("https://oauth.pstmn.io/v1/callback")
                // RP-Initiated Logout: para onde o auth-server devolve o browser após encerrar a sessão
                .postLogoutRedirectUri("http://localhost:5173/")
                .postLogoutRedirectUri("https://app.localhost/") // borda TLS de dev
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("users.read")
                .scope("users.write")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();
    }

}