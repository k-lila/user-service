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

    /**
     * {@code servers[]} RELATIVO — deliberado, não é configuração esquecida.
     *
     * <p>A página do Swagger-UI é servida por origens diferentes conforme o cenário: o nginx do
     * SPA (dev Docker em {@code :5173} e deploy sob hostname único, ADR-019), o proxy do Vite
     * (dev manual) ou o próprio gateway em {@code :8081}. Uma URL absoluta aqui casa com UMA
     * delas; nas outras o "Try it out" vira cross-origin e o browser o barra no
     * {@code connect-src 'self'} da CSP que o nginx emite em {@code /swagger-ui}. O erro chega
     * ao operador como "Failed to fetch", sem diagnóstico.
     *
     * <p>Relativo, o Swagger-UI resolve contra a origem da própria página e a chamada é sempre
     * same-origin: a CSP passa e o cookie {@code SESSION} viaja junto (fetch same-origin envia
     * credenciais por default, e o Swagger-UI não está com {@code withCredentials}), que é o que
     * a rota do gateway converte em Bearer via {@code tokenRelay()}.
     *
     * <p>Não troque por absoluto nem reintroduza {@code API_BASE_URL} aqui: essa variável passou
     * a servir só à base do link de verificação de e-mail
     * ({@code app.verification.base-url}, ADR-015).
     */
    private static final String RELATIVE_SERVER_URL = "/";

    @Value("${AUTH_URL:http://localhost:8082/oauth2/authorize}")
    private String authUrl;
    @Value("${AUTH_TOKEN:http://localhost:8082/oauth2/token}")
    private String tokenUrl;
    @Bean
    public OpenAPI openAPI() {

        return new OpenAPI()
        .servers(List.of(new Server().url(RELATIVE_SERVER_URL)))
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
