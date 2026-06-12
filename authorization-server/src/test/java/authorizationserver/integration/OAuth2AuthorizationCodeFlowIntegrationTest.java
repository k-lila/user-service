package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import jakarta.servlet.http.Cookie;

/**
 * Fluxo OAuth2 authorization_code + PKCE ponta a ponta (C10.1), com a infra real:
 * client {@code gateway-client} semeado no Postgres, sessão de login no Redis
 * (cookie {@code AUTHSESSION}), credenciais buscadas via Feign no WireMock e o
 * JWT assinado/customizado pelos {@code JWKConfig}/{@code TokenCustomizerConfig} reais.
 */
class OAuth2AuthorizationCodeFlowIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CLIENT_ID = "gateway-client";
    private static final String CLIENT_SECRET = "test-client-secret"; // oauth.client.secret do application.yml de teste
    private static final String REDIRECT_URI = "http://localhost:8081/login/oauth2/code/gateway-client";
    private static final String SENHA = "Senha123";
    private static final String SENHA_HASH = new BCryptPasswordEncoder().encode(SENHA);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deveEmitirJwtComClaimsCustomizados_quandoFluxoAuthorizationCodePkceCompleto() throws Exception {
        // ARRANGE — usuário ativo no "user-service" (WireMock) e par PKCE S256
        String email = "fluxo.feliz@test.com";
        stubUsuarioAtivo(email, "user-id-123", "USER");
        String codeVerifier = gerarCodeVerifier();
        String codeChallenge = gerarCodeChallenge(codeVerifier);

        // ACT — 1) authorize sem sessão: request salvo e redirect ao form de login
        MvcResult authorizeAnonimo = mockMvc.perform(authorizeRequest(codeChallenge))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie sessao = authorizeAnonimo.getResponse().getCookie("AUTHSESSION");

        // ACT — 2) form login com credenciais corretas (BCrypt validado contra o stub)
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", SENHA)
                        .cookie(sessao)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie sessaoAutenticada = sessaoAtualizada(login.getResponse(), sessao);

        // ACT — 3) authorize com sessão autenticada: code emitido no redirect_uri
        MvcResult authorizeAutenticado = mockMvc.perform(authorizeRequest(codeChallenge)
                        .cookie(sessaoAutenticada))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String locationComCode = authorizeAutenticado.getResponse().getRedirectedUrl();
        String code = extrairParametro(locationComCode, "code");

        // ACT — 4) troca do code por token: client secret (Basic) + code_verifier (PKCE)
        MvcResult token = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(CLIENT_ID, CLIENT_SECRET))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn();

        // ASSERT — redirecionamentos do fluxo
        assertNotNull(sessao, "authorize anônimo deve criar a sessão AUTHSESSION");
        assertTrue(authorizeAnonimo.getResponse().getRedirectedUrl().endsWith("/login"));
        assertTrue(login.getResponse().getRedirectedUrl().contains("/oauth2/authorize"),
                "login deve voltar ao request de autorização salvo na sessão");
        assertTrue(locationComCode.startsWith(REDIRECT_URI));
        assertNotNull(code, "redirect autenticado deve carregar o authorization code");

        // ASSERT — JWT emitido com os claims customizados (TokenCustomizerConfig/JWKConfig reais)
        Map<String, Object> corpo = JSONObjectUtils.parse(token.getResponse().getContentAsString());
        String accessToken = (String) corpo.get("access_token");
        assertNotNull(accessToken);
        JWTClaimsSet claims = SignedJWT.parse(accessToken).getJWTClaimsSet();
        assertEquals("user-id-123", claims.getStringClaim("userID"));
        assertEquals(List.of("USER"), claims.getStringListClaim("roles"));
        assertEquals(List.of("users.read", "users.write"), claims.getStringListClaim("permissions"));
        assertEquals(email, claims.getSubject());
    }

    @Test
    void deveRetornarAoLoginSemEmitirCodigo_quandoCredenciaisInvalidas() throws Exception {
        // ARRANGE — usuário existe, mas a senha submetida não casa com o hash
        String email = "senha.errada@test.com";
        stubUsuarioAtivo(email, "user-id-456", "USER");
        String codeChallenge = gerarCodeChallenge(gerarCodeVerifier());

        MvcResult authorizeAnonimo = mockMvc.perform(authorizeRequest(codeChallenge))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie sessao = authorizeAnonimo.getResponse().getCookie("AUTHSESSION");

        // ACT — form login com senha errada
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", "SenhaErrada999")
                        .cookie(sessao)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // ASSERT — volta ao form de login com erro; nenhum code emitido
        String location = login.getResponse().getRedirectedUrl();
        assertTrue(location.endsWith("/login?error"));
        assertFalse(location.contains("code="));
    }

    @Test
    void deveRejeitarAutorizacao_quandoRequisicaoSemPkce() throws Exception {
        // ARRANGE/ACT — authorize sem code_challenge (gateway-client tem requireProofKey=true)
        MvcResult resultado = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "estado-teste"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // ASSERT — rejeitada antes do login: erro devolvido ao redirect_uri, sem code
        String location = resultado.getResponse().getRedirectedUrl();
        assertTrue(location.startsWith(REDIRECT_URI));
        assertTrue(location.contains("error=invalid_request"));
        assertFalse(location.contains("code="));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Stub do canal interno Feign: exige o X-Internal-Token injetado pelo FeignConfig. */
    private void stubUsuarioAtivo(String email, String userId, String role) {
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "%s",
                          "email": "%s",
                          "passwordHash": "%s",
                          "active": true,
                          "roles": ["%s"]
                        }
                        """.formatted(userId, email, SENHA_HASH, role))));
    }

    private MockHttpServletRequestBuilder authorizeRequest(String codeChallenge) {
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "openid profile")
                .queryParam("state", "estado-teste")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256");
    }

    /** O login troca o id de sessão (proteção contra session fixation): usa o cookie novo se emitido. */
    private Cookie sessaoAtualizada(MockHttpServletResponse response, Cookie atual) {
        Cookie nova = response.getCookie("AUTHSESSION");
        return nova != null ? nova : atual;
    }

    private String extrairParametro(String url, String nome) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(nome);
    }

    private static String gerarCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String gerarCodeChallenge(String codeVerifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
