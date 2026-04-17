package authorizationserver.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;


@Configuration
public class OAuth2ClientConfig {

    @Bean
	PasswordEncoder passwordEncoder() {
	    return new BCryptPasswordEncoder();
	}


    @Bean
    public RegisteredClientRepository registeredClientRepository() {

        return new InMemoryRegisteredClientRepository(
                gatewayClient(),
                swaggerClient()
                // internalServiceClient()
        );
    }

    private RegisteredClient gatewayClient() {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("gateway-client")
                .clientSecret(passwordEncoder().encode("gateway-secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:8081/login/oauth2/code/gateway-client")
                .redirectUri("http://localhost:8081/swagger-ui/oauth2-redirect.html")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("users.read")
                .scope("users.write")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(false)
                        .build())
                .build();
    }

    private RegisteredClient swaggerClient() {

        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("swagger-client")
                .clientSecret(passwordEncoder().encode("swagger-secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:8081/login/oauth2/code/swagger-client")
                .redirectUri("http://localhost:8081/swagger-ui/oauth2-redirect.html")
                .redirectUri("https://oauth.pstmn.io/v1/callback")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("users.read")
                .scope("users.write")
                .scope("internal")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();
    }

    // private RegisteredClient internalServiceClient() {
    //     return RegisteredClient.withId(UUID.randomUUID().toString())
    //             .clientId("internal-service-client")
    //             .clientSecret("{noop}service-secret")
    //             .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
    //             .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
    //             .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
    //             .scope("internal")
    //             .build();
    // }
}