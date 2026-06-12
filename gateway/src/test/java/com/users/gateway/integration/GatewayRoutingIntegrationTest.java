package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Integração de roteamento: cada path chega ao downstream correto (via lb:// + WireMock),
 * o rewritePath dos api-docs funciona e o X-Correlation-ID é propagado.
 */
class GatewayRoutingIntegrationTest extends AbstractGatewayIntegrationTest {

    @Test
    void deveRotearRegisterParaUserService() {
        downstream.stubFor(post(urlEqualTo("/v1/users/register"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"u1\"}")));

        webTestClient.post().uri("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.com\",\"password\":\"Senha123\",\"name\":\"A\"}")
                .exchange()
                .expectStatus().isCreated();

        downstream.verify(postRequestedFor(urlEqualTo("/v1/users/register")));
    }

    @Test
    void deveRotearOauth2ParaAuthorizationServer() {
        downstream.stubFor(get(urlPathEqualTo("/oauth2/authorize"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        webTestClient.get()
                .uri(b -> b.path("/oauth2/authorize").queryParam("response_type", "code").build())
                .exchange()
                .expectStatus().isOk();

        downstream.verify(getRequestedFor(urlPathEqualTo("/oauth2/authorize")));
    }

    @Test
    void deveReescreverPathDosApiDocsDoUserService() {
        downstream.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        webTestClient.get().uri("/v3/api-docs/user")
                .exchange()
                .expectStatus().isOk();

        // rewritePath: /v3/api-docs/user -> /v3/api-docs
        downstream.verify(getRequestedFor(urlEqualTo("/v3/api-docs")));
    }

    @Test
    void devePropagarCorrelationIdAoDownstream() {
        downstream.stubFor(post(urlEqualTo("/v1/users/register"))
                .willReturn(aResponse().withStatus(201)));

        webTestClient.post().uri("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isCreated();

        downstream.verify(postRequestedFor(urlEqualTo("/v1/users/register"))
                .withHeader("X-Correlation-ID", matching(".+")));
    }
}
