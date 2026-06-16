package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.tomakehurst.wiremock.client.WireMock;

import jakarta.servlet.http.Cookie;

/**
 * Sessão HTTP do auth-server no Redis (Spring Session, {@code @EnableRedisHttpSession}):
 * o fluxo de login emite o cookie {@code AUTHSESSION} (CookieSerializer custom — valor é
 * o session id em Base64) e a sessão correspondente fica persistida em
 * {@code authserver:session:sessions:&lt;id&gt;} no Redis do Testcontainers (redisNamespace
 * dedicado do auth-server).
 */
class RedisSessionIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CLIENT_ID = "gateway-client";
    private static final String REDIRECT_URI = "http://localhost:8081/login/oauth2/code/gateway-client";
    private static final String SENHA = "Senha123";
    private static final String SENHA_HASH = new BCryptPasswordEncoder().encode(SENHA);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void deveEmitirCookieAuthsessionEPersistirSessaoNoRedis_quandoFluxoDeLoginIniciado() throws Exception {
        // ARRANGE/ACT — authorize anônimo salva o authorization request na sessão
        MvcResult authorize = mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie sessao = authorize.getResponse().getCookie("AUTHSESSION");

        // ASSERT — cookie emitido e sessão correspondente gravada no Redis
        assertNotNull(sessao, "iniciar o fluxo de login deve emitir o cookie AUTHSESSION");
        assertTrue(redisTemplate.hasKey(chaveDaSessao(sessao)),
                "a sessão do cookie AUTHSESSION deve estar persistida no Redis (authserver:session)");
    }

    @Test
    void deveManterSessaoAutenticadaNoRedis_quandoLoginBemSucedido() throws Exception {
        // ARRANGE — usuário ativo no "user-service" (WireMock) e fluxo iniciado
        String email = "sessao.redis@test.com";
        stubUsuarioAtivo(email);
        MvcResult authorize = mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie sessao = authorize.getResponse().getCookie("AUTHSESSION");

        // ACT — login com a sessão anônima (session fixation pode trocar o id → cookie novo)
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", SENHA)
                        .cookie(sessao)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie sessaoAutenticada = Optional.ofNullable(login.getResponse().getCookie("AUTHSESSION"))
                .orElse(sessao);

        // ASSERT — o redirect de volta ao /oauth2/authorize prova que o authorization request
        // salvo na sessão Redis sobreviveu entre os requests; a sessão autenticada está no Redis
        assertTrue(login.getResponse().getRedirectedUrl().contains("/oauth2/authorize"),
                "o login deve retomar o authorization request guardado na sessão Redis");
        assertTrue(redisTemplate.hasKey(chaveDaSessao(sessaoAutenticada)),
                "a sessão autenticada deve estar persistida no Redis");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Chave Spring Session no Redis: o valor do cookie é o session id em Base64.
     *  Prefixo = redisNamespace dedicado do auth-server (authserver:session). */
    private String chaveDaSessao(Cookie cookie) {
        String sessionId = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
        return "authserver:session:sessions:" + sessionId;
    }

    private void stubUsuarioAtivo(String email) {
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "id-sessao",
                          "email": "%s",
                          "passwordHash": "%s",
                          "active": true,
                          "roles": ["USER"]
                        }
                        """.formatted(email, SENHA_HASH))));
    }

    /** Authorize válido (PKCE S256) — cria a sessão anônima com o request salvo. */
    private MockHttpServletRequestBuilder authorizeRequest() throws Exception {
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "openid profile")
                .queryParam("state", "estado-teste")
                .queryParam("code_challenge", gerarCodeChallenge())
                .queryParam("code_challenge_method", "S256");
    }

    private static String gerarCodeChallenge() throws Exception {
        byte[] verifier = new byte[32];
        new SecureRandom().nextBytes(verifier);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Base64.getUrlEncoder().withoutPadding().encodeToString(verifier)
                        .getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
