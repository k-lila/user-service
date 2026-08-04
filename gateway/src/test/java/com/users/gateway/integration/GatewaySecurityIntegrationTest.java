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
    void deveRetornar401_quandoRotaGenericaDeUsersSemAutenticacao() {
        // /v1/users/me cai na rota genérica "user-service" (tokenRelay), não nas rotas públicas
        // pré-sessão de verificação de e-mail — deve continuar exigindo sessão.
        webTestClient.get().uri("/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void devePermitirVerifyEmailSemAutenticacao() {
        downstream.stubFor(get(urlPathEqualTo("/v1/users/verify-email"))
                .willReturn(aResponse().withStatus(200)));

        webTestClient.get().uri("/v1/users/verify-email?token=abc")
                .exchange()
                .expectStatus().isOk(); // não 401
    }

    @Test
    void deveRetornar403_quandoResendVerificationSemTokenCsrf() {
        // resend-verification deixou de ser pré-sessão (ADR-015): hoje é self-service
        // autenticado, coberto pela rota genérica "user-service" — exige CSRF como qualquer
        // POST autenticado (a checagem de CSRF antecede a de autenticação na cadeia de filtros).
        webTestClient.post().uri("/v1/users/resend-verification")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
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

    /**
     * ADR-020 — a documentação deixou de ser pública. O vazamento que motivou a mudança foi o
     * springdoc materializar `springdoc.swagger-ui.oauth.client-secret` como um ui.initOAuth()
     * literal dentro de /swagger-ui/swagger-initializer.js, servido a anônimos. O bloco `oauth`
     * saiu do gateway.yml, mas o gate de acesso é a garantia durável: qualquer coisa que uma
     * configuração futura empurre para dentro da página deixa de ser legível sem sessão.
     *
     * Espera-se 302 (não 401) porque /swagger-ui/** é navegação de browser, e um 401 seco não
     * daria ao operador caminho para autenticar — ver swaggerAwareEntryPoint() no SecurityConfig.
     */
    @Test
    void deveRedirecionarParaLogin_quandoSwaggerUiSemAutenticacao() {
        webTestClient.get().uri("/swagger-ui/index.html")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().value("Location",
                        location -> org.assertj.core.api.Assertions.assertThat(location)
                                .contains("/oauth2/authorization/gateway-client"));
    }

    /**
     * ADR-020 — o swagger-initializer.js é o arquivo que carregava o client secret. Sob o gate,
     * um anônimo não o alcança. Regressão direta do vazamento.
     */
    @Test
    void deveRedirecionarParaLogin_quandoSwaggerInitializerSemAutenticacao() {
        webTestClient.get().uri("/swagger-ui/swagger-initializer.js")
                .exchange()
                .expectStatus().isFound();
    }

    /**
     * ADR-020 — /v3/api-docs/** fica autenticado, mas com o entry point DEFAULT (401), não com o
     * redirect: é caminho de XHR (o JS da página busca o doc). Um 302 para o HTML do login faria
     * o swagger-client tentar parsear a tela de login como JSON. Este teste fixa a distinção —
     * é a razão de o matcher do delegating cobrir só /swagger-ui/**.
     */
    @Test
    void deveRetornar401SemRedirect_quandoApiDocsSemAutenticacao() {
        webTestClient.get().uri("/v3/api-docs/user")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Guarda de que o entry point default continua 401 depois de o delegating entrar (ADR-020).
     * Se o redirect vazasse para as rotas de API, o SPA passaria a receber HTML de login onde
     * espera JSON — quebra silenciosa do BFF.
     */
    @Test
    void deveManter401SemRedirect_emRotaDeApiAposDelegatingEntryPoint() {
        webTestClient.get().uri("/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Location");
    }

    /**
     * Risco latente P3 — POST /oauth2/authorize (formulário de consentimento, ADR-019):
     * se requireAuthorizationConsent for habilitado no futuro, o auth-server renderiza um
     * formulário de consentimento; o browser submete POST /oauth2/authorize sem o
     * XSRF-TOKEN do gateway → 403. Mesma classe do BUG-001. Hoje não é acionado porque
     * OAuth2ClientConfig.gatewayClient() usa requireAuthorizationConsent(false).
     * Este teste documenta o bloqueio atual e serve de rede de segurança: se o
     * consentimento for ativado sem adicionar /oauth2/authorize à lista de isenções, o
     * login quebrará da mesma forma que BUG-001. (ADR-019)
     */
    @Test
    void postOAuth2AuthorizeSemCsrf_deveRetornar403_riscoLatenteSeDomainConsentAtivado() {
        webTestClient.post().uri("/oauth2/authorize")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("client_id=gateway-client&scope=openid&_csrf=auth-server-csrf-value")
                .exchange()
                .expectStatus().isForbidden();
    }

    /**
     * Risco latente P3 — POST /connect/logout (confirmação de logout, ADR-018/ADR-019):
     * se o auth-server exibir uma tela de confirmação de logout (ex.: quando id_token_hint
     * está ausente ou inválido), o browser submeteria POST /connect/logout sem o
     * XSRF-TOKEN do gateway → 403. No fluxo atual, oidcLogoutSuccessHandler sempre envia
     * id_token_hint; com hint válido, o Spring Authorization Server dispensa a confirmação.
     * Este teste documenta o bloqueio atual. (ADR-019)
     */
    @Test
    void postConnectLogoutSemCsrf_deveRetornar403_riscoLatenteSemIdTokenHint() {
        webTestClient.post().uri("/connect/logout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("_csrf=auth-server-csrf-value")
                .exchange()
                .expectStatus().isForbidden();
    }

    /**
     * Regressão BUG-001 (P1, ADR-019): POST /login deve atravessar o gateway sem ser barrado
     * por CSRF, mesmo sem X-XSRF-TOKEN válido do gateway.
     *
     * Contexto: sob hostname único (Cloudflare Tunnel), o nginx encaminha POST /login ao gateway,
     * que o proxia ao auth-server. O formulário do auth-server embute um _csrf gerado pelo
     * próprio auth-server — o gateway não tem como injetar seu XSRF-TOKEN num HTML renderizado
     * pelo auth-server. Exigir o token do gateway em /login é incorreto: a request é
     * unauthenticated (sem sessão do gateway a proteger). O auth-server é dono do CSRF do
     * formulário de login. (ADR-019)
     */
    @Test
    void postLoginSemCsrfDoGateway_deveChegarAoDownstream() {
        downstream.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "http://localhost/login?error")));

        webTestClient.post().uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("username=test&password=wrong&_csrf=auth-server-csrf-value")
                .exchange()
                .expectStatus().is3xxRedirection(); // chegou ao auth-server; gateway não bloqueou em 403
    }
}
