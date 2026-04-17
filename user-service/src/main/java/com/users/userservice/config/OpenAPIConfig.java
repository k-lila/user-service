package com.users.userservice.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenAPIConfig {

    @Value("${API_BASE_URL:http://localhost:8081}")
    private String apiBaseUrl;
    @Value("${AUTH_URL:http://localhost:8082/oauth2/authorize}")
    private String authUrl;
    @Value("${AUTH_TOKEN:http://localhost:8082/oauth2/token}")
    private String tokenUrl;
    @Bean
    public OpenAPI openAPI() {

        return new OpenAPI()
        .servers(List.of(new Server().url(apiBaseUrl)))
            .components(new Components()
                .addSecuritySchemes("oauth2",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(new OAuthFlows()
                            .authorizationCode(
                                new OAuthFlow()
                                    .authorizationUrl(authUrl)
                                    .tokenUrl(tokenUrl)
                            )
                        )
                )
            )
            .addSecurityItem(new SecurityRequirement().addList("oauth2"));
    }
}