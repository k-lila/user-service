package com.users.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Unitário do {@link OpenAPIConfig}: scheme OAUTH2 (authorization code) com as URLs
 * de authorize/token vindas da config e scopes openid/profile.
 */
class OpenAPIConfigTest {

    @Test
    void deveConfigurarSecuritySchemeOauth2ComUrlsDaConfig() {
        OpenAPIConfig config = new OpenAPIConfig();
        ReflectionTestUtils.setField(config, "authUrl", "http://auth/authorize");
        ReflectionTestUtils.setField(config, "tokenUrl", "http://auth/token");

        OpenAPI api = config.gatewayOpenAPI();

        SecurityScheme scheme = api.getComponents().getSecuritySchemes().get("oauth2");
        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.OAUTH2);

        OAuthFlow flow = scheme.getFlows().getAuthorizationCode();
        assertThat(flow.getAuthorizationUrl()).isEqualTo("http://auth/authorize");
        assertThat(flow.getTokenUrl()).isEqualTo("http://auth/token");
        assertThat(flow.getScopes()).containsKeys("openid", "profile");

        assertThat(api.getSecurity())
                .anySatisfy(req -> assertThat(req).containsKey("oauth2"));
    }
}
