package authorizationserver.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    public RegisteredClientRepository registeredClientRepository() {

        RegisteredClient gatewayClient =
            RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("gateway-client")
                .clientSecret("{noop}gateway-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)

                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)

                .redirectUri("http://localhost:8081/login/oauth2/code/gateway")

                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)

                .clientSettings(
                    ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build()
                )
                .build();


        RegisteredClient swaggerClient =
            RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("swagger-client")
                .clientSecret("{noop}swagger-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)

                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)

                .scope("internal")

                .build();


        RegisteredClient serviceClient =
            RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("internal-service-client")
                .clientSecret("{noop}service-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)

                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)

                .scope("internal")

                .build();


        return new InMemoryRegisteredClientRepository(
            gatewayClient,
            swaggerClient,
            serviceClient
        );
    }
}