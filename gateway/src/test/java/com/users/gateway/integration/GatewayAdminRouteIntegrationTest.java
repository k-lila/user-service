package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integração da rota {@code /v1/admin/**} (C-2/AC-13): roteamento ao user-service via
 * {@code lb://}, CSRF nas mutações (PATCH), rate limiter MED (não HIGH) por usuário e 401
 * sem autenticação. O {@code @PreAuthorize("hasRole('ADMIN')")} é responsabilidade do
 * user-service (coberto em {@code AdminControllerTest}/{@code AdminFlowIntegrationTest});
 * aqui só a borda (roteamento/CSRF/rate limit) é exercitada.
 */
class GatewayAdminRouteIntegrationTest extends AbstractGatewayIntegrationTest {

    @Test
    void deveRotearAdminUsersParaUserService_quandoAutenticado() {
        downstream.stubFor(get(urlPathEqualTo("/v1/admin/users"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("[]")));

        WebTestClient secured = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient().build();

        secured.mutateWith(mockJwt())
                .get().uri("/v1/admin/users")
                .exchange()
                .expectStatus().isOk();

        downstream.verify(getRequestedFor(urlPathEqualTo("/v1/admin/users")));
    }

    @Test
    void deveRetornar401_quandoAdminRouteSemAutenticacao() {
        webTestClient.get().uri("/v1/admin/users")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void deveRetornar403PorCsrf_quandoPatchRolesSemTokenXsrf() {
        WebTestClient secured = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient().build();

        secured.mutateWith(mockJwt())
                .patch().uri("/v1/admin/users/u1/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"roles\":[\"ADMIN\"]}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void devePermitirPatchRoles_quandoComTokenXsrfCorreto() {
        downstream.stubFor(patch(urlPathEqualTo("/v1/admin/users/u1/roles"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        WebTestClient secured = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient().build();

        String xsrfToken = "test-xsrf-token";

        secured.mutateWith(mockJwt())
                .patch().uri("/v1/admin/users/u1/roles")
                .cookie("XSRF-TOKEN", xsrfToken)
                .header("X-XSRF-TOKEN", xsrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"roles\":[\"ADMIN\"]}")
                .exchange()
                .expectStatus().isOk();

        downstream.verify(patchRequestedFor(urlPathEqualTo("/v1/admin/users/u1/roles")));
    }

    @Test
    void deveAplicarLimiterMed_naRotaAdmin() {
        downstream.stubFor(get(urlPathEqualTo("/v1/admin/users"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("[]")));

        WebTestClient secured = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient().build();

        int rejeitadas = 0;
        for (int i = 0; i < 25; i++) {
            int status = secured.mutateWith(mockJwt())
                    .get().uri("/v1/admin/users")
                    .exchange()
                    .returnResult(Void.class).getStatus().value();
            if (status == 429) {
                rejeitadas++;
            }
        }

        assertThat(rejeitadas)
                .as("limiter MED (5 rps, burst 10): uma rajada de 25 requisições do mesmo "
                        + "usuário deve estourar o burst")
                .isGreaterThan(0);
    }
}
