package com.users.userservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Unitário do {@link OpenAPIConfig}: {@code servers[]} relativo e scheme OAUTH2 (authorization
 * code) com as URLs de authorize/token vindas da config.
 */
class OpenAPIConfigTest {

    /**
     * Regressão: um {@code servers[]} ABSOLUTO amarra o doc a uma única origem. Como a página do
     * Swagger-UI é servida ora pelo nginx do SPA, ora pelo Vite, ora pelo gateway, a origem que
     * não casar faz o "Try it out" virar cross-origin — e o {@code connect-src 'self'} da CSP do
     * nginx o barra com um "Failed to fetch" sem diagnóstico. Relativo, resolve contra a origem
     * da página e é sempre same-origin. Ver o javadoc de {@link OpenAPIConfig}.
     */
    @Test
    void deveAnunciarServerRelativoParaResolverContraAOrigemDaPagina() {
        OpenAPI api = novaConfig().openAPI();

        assertThat(api.getServers())
                .singleElement()
                .satisfies(server -> {
                    assertThat(server.getUrl()).isEqualTo("/");
                    assertThat(server.getUrl())
                            .as("servers[] absoluto reintroduz o bug de origem divergente")
                            .doesNotStartWith("http");
                });
    }

    @Test
    void deveConfigurarSecuritySchemeOauth2ComUrlsDaConfig() {
        OpenAPI api = novaConfig().openAPI();

        SecurityScheme scheme = api.getComponents().getSecuritySchemes().get("oauth2");
        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.OAUTH2);

        OAuthFlow flow = scheme.getFlows().getAuthorizationCode();
        assertThat(flow.getAuthorizationUrl()).isEqualTo("http://auth/authorize");
        assertThat(flow.getTokenUrl()).isEqualTo("http://auth/token");

        assertThat(api.getSecurity())
                .anySatisfy(req -> assertThat(req).containsKey("oauth2"));
    }

    private OpenAPIConfig novaConfig() {
        OpenAPIConfig config = new OpenAPIConfig();
        ReflectionTestUtils.setField(config, "authUrl", "http://auth/authorize");
        ReflectionTestUtils.setField(config, "tokenUrl", "http://auth/token");
        return config;
    }
}
