package authorizationserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenAPIConfig {

   @Bean
    public OpenAPI api() {
        return new OpenAPI()
            .components(new Components()
                .addSecuritySchemes("oauth2",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(new OAuthFlows()
                            .authorizationCode(
                                new OAuthFlow()
                                    .authorizationUrl("http://localhost:8082/oauth2/authorize")
                                    .tokenUrl("http://localhost:8082/oauth2/token")
                                    .scopes(new Scopes()
                                        .addString("openid", "OpenID")
                                        .addString("profile", "Profile")
                                    )
                            )
                        )
                )
            )
            .addSecurityItem(new SecurityRequirement().addList("oauth2"));
    }
}