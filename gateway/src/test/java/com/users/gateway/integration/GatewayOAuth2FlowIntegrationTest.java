package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Integração ponta a ponta do fluxo BFF OAuth2 (C10.7): {@code authorization_code} + PKCE +
 * OIDC, exercitado contra a porta real. Cobre os dois caminhos que faltavam:
 * <ul>
 *   <li><b>TokenRelay</b> — após o login real, {@code GET /v1/users/me} chega ao downstream
 *       com {@code Authorization: Bearer <access_token>} da sessão BFF.</li>
 *   <li><b>Logout RP-initiated</b> — {@code POST /logout} redireciona ao
 *       {@code end_session_endpoint} com {@code id_token_hint}.</li>
 * </ul>
 *
 * <p>O id_token é minerado e assinado de verdade (RSA/RS256) e sua chave pública é servida no
 * {@code jwk-set-uri} (WireMock), então a validação de assinatura/claims do OIDC roda real. O
 * {@code nonce} do id_token reusa o parâmetro {@code nonce} capturado da URL de autorização: o
 * Spring envia ali o hash do nonce e compara o claim contra esse mesmo hash, então não há hash
 * a reimplementar.
 */
class GatewayOAuth2FlowIntegrationTest extends AbstractGatewayIntegrationTest {

    private static final String ACCESS_TOKEN = "relayed-access-token";
    private static final String SUBJECT = "user-123";

    // Par RSA fixo da execução: assina o id_token e alimenta o JWKS servido no jwk-set-uri.
    private static final RSAKey RSA_KEY = generateRsaKey();

    /**
     * Aponta token/jwks/userinfo do provider ao WireMock compartilhado. A base mantém esses
     * endpoints em localhost:9 (buraco negro) para o boot não tocar a rede; aqui o fluxo real
     * precisa alcançá-los. ({@code authorization-uri} continua inerte — é front-channel, só
     * parseamos o redirect.) userinfo é necessário porque o escopo {@code profile} faz o
     * OidcReactiveOAuth2UserService chamar o endpoint.
     */
    @DynamicPropertySource
    static void oauthProviderEndpoints(DynamicPropertyRegistry registry) {
        String base = "spring.security.oauth2.client.provider.authorization-server.";
        registry.add(base + "token-uri", () -> downstream.baseUrl() + "/oauth2/token");
        registry.add(base + "jwk-set-uri", () -> downstream.baseUrl() + "/oauth2/jwks");
        registry.add(base + "user-info-uri", () -> downstream.baseUrl() + "/userinfo");
    }

    @Test
    void deveRedirecionarParaAutorizacaoComPkce() {
        EntityExchangeResult<byte[]> result = webTestClient.get()
                .uri("/oauth2/authorization/gateway-client")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectBody().returnResult();

        String location = result.getResponseHeaders().getLocation().toString();
        MultiValueMap<String, String> params =
                UriComponentsBuilder.fromUriString(location).build().getQueryParams();

        assertThat(params.getFirst("response_type")).isEqualTo("code");
        assertThat(params.getFirst("client_id")).isEqualTo("gateway-client");
        assertThat(params.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(params.getFirst("code_challenge")).isNotBlank();
        assertThat(params.getFirst("state")).isNotBlank();
        assertThat(params.getFirst("nonce")).isNotBlank();
        assertThat(params.getFirst("scope")).contains("openid");
    }

    @Test
    void deveRelayarBearerAoDownstreamAposLogin() {
        LoginResult login = performRealLogin();

        downstream.stubFor(get(urlPathEqualTo("/v1/users/me"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        webTestClient.get().uri("/v1/users/me")
                .cookie("SESSION", login.sessionCookie())
                .exchange()
                .expectStatus().isOk();

        // O TokenRelay leu o access token da sessão BFF e o injetou no downstream.
        downstream.verify(getRequestedFor(urlPathEqualTo("/v1/users/me"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN)));
    }

    @Test
    void deveDispararLogoutRpInitiadoComIdTokenHint() {
        LoginResult login = performRealLogin();

        EntityExchangeResult<byte[]> result = webTestClient.post().uri("/logout")
                .cookie("SESSION", login.sessionCookie())
                .cookie("XSRF-TOKEN", login.xsrfToken())
                .header("X-XSRF-TOKEN", login.xsrfToken())
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectBody().returnResult();

        String location = result.getResponseHeaders().getLocation().toString();
        // Default OAUTH_END_SESSION_URI; id_token_hint só tem chars URL-safe (JWT) → aparece verbatim.
        assertThat(location).startsWith("http://localhost:8082/connect/logout");
        assertThat(location).contains("id_token_hint=" + login.idToken());
        assertThat(location).contains("post_logout_redirect_uri=");
    }

    /**
     * Dirige o fluxo real até a sessão autenticada: inicia o oauth2Login (302 com PKCE/nonce),
     * stuba token/jwks/userinfo, completa o callback com o {@code state} capturado e devolve os
     * cookies + tokens resultantes.
     */
    private LoginResult performRealLogin() {
        // 1) Início do login: 302 ao authorization-uri com state/nonce/PKCE; cria a sessão.
        EntityExchangeResult<byte[]> authzResult = webTestClient.get()
                .uri("/oauth2/authorization/gateway-client")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectBody().returnResult();

        String location = authzResult.getResponseHeaders().getLocation().toString();
        MultiValueMap<String, String> params =
                UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        // getQueryParams devolve os valores ainda codificados (o state base64 traz '=' como %3D);
        // decodificamos para reenviar ao callback sem dupla codificação.
        String state = decode(params.getFirst("state"));
        String nonce = decode(params.getFirst("nonce"));
        String sessionCookie = authzResult.getResponseCookies().getFirst("SESSION").getValue();
        // CSRF é double-submit cookie (CookieServerCsrfTokenRepository): o servidor confia no
        // cookie como fonte de verdade, então basta cookie == header. Se o redirect não emitir o
        // cookie, um valor próprio serve igualmente para o POST /logout.
        var xsrfCookie = authzResult.getResponseCookies().getFirst("XSRF-TOKEN");
        String xsrfToken = xsrfCookie != null ? xsrfCookie.getValue() : "test-xsrf-token";

        // 2) Dublês do back-channel: token (com id_token assinado), JWKS e userinfo.
        String idToken = signIdToken(nonce);
        downstream.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tokenResponse(idToken))));
        downstream.stubFor(get(urlPathEqualTo("/oauth2/jwks"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(RSA_KEY.toPublicJWK()).toString())));
        downstream.stubFor(get(urlPathEqualTo("/userinfo"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sub\":\"" + SUBJECT + "\"}")));

        // 3) Callback: troca o código, valida o id_token e autentica a sessão (fixation → novo id).
        EntityExchangeResult<byte[]> callbackResult = webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/login/oauth2/code/gateway-client")
                        .queryParam("code", "test-code")
                        .queryParam("state", state)
                        .build())
                .cookie("SESSION", sessionCookie)
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectBody().returnResult();

        var refreshedSession = callbackResult.getResponseCookies().getFirst("SESSION");
        String authenticatedSession =
                refreshedSession != null ? refreshedSession.getValue() : sessionCookie;

        return new LoginResult(authenticatedSession, xsrfToken, ACCESS_TOKEN, idToken);
    }

    private String tokenResponse(String idToken) {
        return "{"
                + "\"access_token\":\"" + ACCESS_TOKEN + "\","
                + "\"token_type\":\"Bearer\","
                + "\"expires_in\":3600,"
                + "\"refresh_token\":\"refresh-token\","
                + "\"scope\":\"openid profile\","
                + "\"id_token\":\"" + idToken + "\""
                + "}";
    }

    private String signIdToken(String nonce) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("http://localhost:8082")
                    .subject(SUBJECT)
                    .audience("gateway-client")
                    .expirationTime(Date.from(now.plusSeconds(3600)))
                    .issueTime(Date.from(now))
                    .claim("nonce", nonce)
                    .claim("azp", "gateway-client")
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(RSA_KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar id_token de teste", e);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static RSAKey generateRsaKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID("test-id-token-key")
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar chave RSA de teste", e);
        }
    }

    private record LoginResult(String sessionCookie, String xsrfToken, String accessToken,
            String idToken) {
    }
}
