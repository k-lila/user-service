package com.users.gateway.config;

import org.springframework.beans.factory.annotation.Value;
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

    @Value("${AUTH_URL:http://localhost:8082/oauth2/authorize}")
    private String authUrl;
    @Value("${AUTH_TOKEN:http://localhost:8082/oauth2/token}")
    private String tokenUrl;

    @Bean
    public OpenAPI gatewayOpenAPI() {

        SecurityScheme securityScheme =
                new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(
                                new OAuthFlows()
                                        .authorizationCode(
                                                new OAuthFlow()
                                                        .authorizationUrl(authUrl)
                                                        .tokenUrl(tokenUrl)
                                                        .scopes(
                                                                new Scopes()
                                                                        .addString("openid", "OpenID")
                                                                        .addString("profile", "Profile")
                                                        )
                                        )
                        );
        return new OpenAPI()
                .components(
                        new Components()
                                .addSecuritySchemes("oauth2", securityScheme)
                )
                .addSecurityItem(new SecurityRequirement().addList("oauth2"));
    }
}