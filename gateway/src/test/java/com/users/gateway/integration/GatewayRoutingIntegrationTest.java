package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

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

    /**
     * ADR-018: o front-channel do RP-Initiated Logout precisa existir na borda. O browser chega
     * aqui DEPOIS de o POST /logout encerrar a sessão do gateway — ou seja, sem autenticação.
     * Sem a rota, 404; fora do permitAll, 401. O teste cobre os dois de uma vez.
     */
    @Test
    void deveRotearConnectLogoutParaAuthorizationServerSemAutenticacao() {
        downstream.stubFor(get(urlPathEqualTo("/connect/logout"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "http://localhost:5173/")));

        webTestClient.get()
                .uri(b -> b.path("/connect/logout")
                        .queryParam("id_token_hint", "eyJhbGciOiJub25lIn0.e30.")
                        .queryParam("post_logout_redirect_uri", "http://localhost:5173/")
                        .build())
                .exchange()
                .expectStatus().isFound();

        downstream.verify(getRequestedFor(urlPathEqualTo("/connect/logout")));
    }

    /**
     * ADR-020: /v3/api-docs/** deixou de ser público — exige sessão. O teste passou a autenticar
     * porque o objeto sob verificação aqui é o <em>rewritePath</em>, não o controle de acesso
     * (esse é coberto por GatewaySecurityIntegrationTest). Sem o mockJwt a request pararia em 401
     * antes de chegar ao roteamento, e o teste deixaria de exercitar o que se propõe.
     */
    @Test
    void deveReescreverPathDosApiDocsDoUserService() {
        downstream.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        // mockJwt só funciona com cliente ligado ao contexto (não contra a porta real).
        WebTestClient secured = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient().build();

        secured.mutateWith(mockJwt())
                .get().uri("/v3/api-docs/user")
                .exchange()
                .expectStatus().isOk();

        // rewritePath: /v3/api-docs/user -> /v3/api-docs
        downstream.verify(getRequestedFor(urlEqualTo("/v3/api-docs")));
    }

    /**
     * ADR-019: /default-ui.css é o CSS do formulário de login do IdP. O browser solicita sem sessão
     * (antes de autenticar). Sem rota no gateway → o path não chegaria ao auth-server. Sem
     * permitAll() no SecurityConfig → 401 antes de rotear. O teste verifica os dois de uma vez:
     * a request chega ao downstream (roteamento) E sem precisar de autenticação (permitAll).
     */
    @Test
    void deveRotearDefaultUiCssParaAuthorizationServerSemAutenticacao() {
        downstream.stubFor(get(urlEqualTo("/default-ui.css"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/css")
                        .withBody("body { font-family: sans-serif; }")));

        webTestClient.get().uri("/default-ui.css")
                // sem cookie de sessão nem Bearer — unauthenticated request
                .exchange()
                .expectStatus().isOk();

        downstream.verify(getRequestedFor(urlEqualTo("/default-ui.css")));
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
