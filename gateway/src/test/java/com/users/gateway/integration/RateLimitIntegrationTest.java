package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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

    /**
     * AC-09 (ADR-019): rota auth-login (/login) usa redisRateLimiterMed (5 rps, burst 10)
     * com ipKeyResolver. Uma rajada concentrada do mesmo IP deve exceder o burst e receber 429.
     *
     * Nota de implementação: o teste usa GET /login por simplicidade — GET é o método natural
     * do browser ao carregar o formulário de login e não requer payload de formulário. GET e
     * POST da rota auth-login compartilham o mesmo balde Redis, portanto o teste verifica que
     * o rate limiter MED está corretamente configurado para o path. POST /login foi isentado
     * do CSRF do gateway no BUG-001 (ADR-019) e também aciona o mesmo balde.
     */
    @Test
    void deveRetornar429_quandoEstourarLimiteMedDeAuthLogin() {
        downstream.stubFor(get(urlPathEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody("<form></form>")));

        int rejeitadas = 0;
        for (int i = 0; i < 30; i++) {
            int status = webTestClient.get().uri("/login")
                    .header("CF-Connecting-IP", "203.0.113.52")
                    .exchange()
                    .returnResult(Void.class).getStatus().value();
            if (status == 429) {
                rejeitadas++;
            }
        }

        assertThat(rejeitadas)
                .as("rajada de 30 req GET /login do mesmo IP deve exceder o burst (10) do tier MED")
                .isGreaterThan(0);
    }
}
