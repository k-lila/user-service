package com.users.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Unitário do {@link CORSConfig}: a allowlist vem da config e usa originPatterns
 * (compatível com allowCredentials), com methods/headers/exposed esperados.
 */
class CORSConfigTest {

    @Test
    void deveConfigurarCorsComOriginsDaConfig() {
        CORSConfig config = new CORSConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins",
                List.of("http://localhost:5173", "https://app.exemplo.com"));

        CorsConfigurationSource source = config.corsConfigurationSource();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/users/me"));
        CorsConfiguration cors = source.getCorsConfiguration(exchange);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOriginPatterns())
                .containsExactly("http://localhost:5173", "https://app.exemplo.com");
        // setAllowedOrigins não deve ser usado (incompatível com allowCredentials + curinga)
        assertThat(cors.getAllowedOrigins()).isNull();
        assertThat(cors.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
        assertThat(cors.getAllowedHeaders()).containsExactly("*");
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getExposedHeaders()).containsExactly("Authorization");
    }
}
