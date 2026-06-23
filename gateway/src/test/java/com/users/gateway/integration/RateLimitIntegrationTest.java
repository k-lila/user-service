package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Integração de rate limiting: o limiter LOW (2 rps, burst 5) do /v1/users/register barra
 * uma rajada concentrada do mesmo IP com HTTP 429. /v1/users/verify-email (ADR-015) compartilha
 * o mesmo tier LOW (pré-sessão, anti-abuso/enumeração); /v1/users/resend-verification deixou de
 * ser pré-sessão e hoje cai no tier HIGH por-usuário da rota genérica "user-service".
 */
class RateLimitIntegrationTest extends AbstractGatewayIntegrationTest {

    @Test
    void deveRetornar429_quandoEstourarLimiteLowDeRegistro() {
        downstream.stubFor(post(urlEqualTo("/v1/users/register"))
                .willReturn(aResponse().withStatus(201)));

        int rejeitadas = 0;
        for (int i = 0; i < 25; i++) {
            int status = webTestClient.post().uri("/v1/users/register")
                    .header("CF-Connecting-IP", "203.0.113.50")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .returnResult(Void.class).getStatus().value();
            if (status == 429) {
                rejeitadas++;
            }
        }

        assertThat(rejeitadas)
                .as("uma rajada de 25 requisições deve exceder o burst (5) do limiter LOW")
                .isGreaterThan(0);
    }

    @Test
    void deveRetornar429_quandoEstourarLimiteLowDeVerifyEmail() {
        downstream.stubFor(get(urlEqualTo("/v1/users/verify-email?token=abc"))
                .willReturn(aResponse().withStatus(200)));

        int rejeitadas = 0;
        for (int i = 0; i < 25; i++) {
            int status = webTestClient.get().uri("/v1/users/verify-email?token=abc")
                    .header("CF-Connecting-IP", "203.0.113.51")
                    .exchange()
                    .returnResult(Void.class).getStatus().value();
            if (status == 429) {
                rejeitadas++;
            }
        }

        assertThat(rejeitadas)
                .as("verify-email deve cair no mesmo tier LOW de /v1/users/register")
                .isGreaterThan(0);
    }
}
