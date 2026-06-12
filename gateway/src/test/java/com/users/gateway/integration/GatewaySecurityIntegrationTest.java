package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integração de segurança (BFF): rota protegida sem auth devolve 401 (não 302); /register
 * é isento de CSRF; o cookie XSRF-TOKEN é emitido; CORS responde à origem permitida; e o
 * acesso autenticado passa pela camada de autorização.
 */
class GatewaySecurityIntegrationTest extends AbstractGatewayIntegrationTest {

    @Test
    void deveRetornar401_quandoRotaProtegidaSemAutenticacao() {
        webTestClient.get().uri("/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void devePermitirRegisterSemTokenCsrf() {
        downstream.stubFor(post(urlEqualTo("/v1/users/register"))
                .willReturn(aResponse().withStatus(201)));

        webTestClient.post().uri("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isCreated(); // não 403
    }

    @Test
    void deveEmitirCookieXsrfTokenEmRotaPublica() {
        downstream.stubFor(get(urlPathEqualTo("/oauth2/authorize"))
                .willReturn(aResponse().withStatus(200)));

        webTestClient.get().uri("/oauth2/authorize")
                .exchange()
                .expectCookie().exists("XSRF-TOKEN");
    }

    @Test
    void deveResponderPreflightCorsParaOrigemPermitida() {
        webTestClient.options().uri("/v1/users/me")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    void devePermitirAcessoAutenticado() {
        downstream.stubFor(get(urlPathEqualTo("/v1/users/me"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        // mockJwt só funciona com cliente ligado ao contexto (não contra a porta real).
        WebTestClient secured = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient().build();

        secured.mutateWith(mockJwt())
                .get().uri("/v1/users/me")
                .exchange()
                .expectStatus().isOk();
    }
}
