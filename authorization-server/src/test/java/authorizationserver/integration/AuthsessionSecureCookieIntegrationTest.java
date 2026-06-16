package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;

/**
 * Flag {@code Secure} do cookie {@code AUTHSESSION} (Ponto 2 — paridade com o gateway):
 * o {@code DefaultCookieSerializer} honra {@code app.cookie.secure} via
 * {@code setUseSecureCookie}. Com a flag ligada (cenário TLS na borda), o
 * {@code Set-Cookie: AUTHSESSION} deve sair com {@code Secure}.
 *
 * <p>Override de propriedade por classe → contexto Spring dedicado (os testes que rodam
 * com o default {@code false} provam que dev HTTP puro segue sem {@code Secure}).
 */
@TestPropertySource(properties = "app.cookie.secure=true")
class AuthsessionSecureCookieIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CLIENT_ID = "gateway-client";
    private static final String REDIRECT_URI = "http://localhost:8081/login/oauth2/code/gateway-client";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deveEmitirAuthsessionComSecure_quandoAppCookieSecureLigado() throws Exception {
        // ACT — authorize anônimo emite o cookie AUTHSESSION da sessão de login
        MvcResult authorize = mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie sessao = authorize.getResponse().getCookie("AUTHSESSION");

        // ASSERT — com app.cookie.secure=true o cookie sai com a flag Secure
        assertNotNull(sessao, "iniciar o fluxo de login deve emitir o cookie AUTHSESSION");
        assertTrue(sessao.getSecure(),
                "com app.cookie.secure=true o cookie AUTHSESSION deve trazer a flag Secure");
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
