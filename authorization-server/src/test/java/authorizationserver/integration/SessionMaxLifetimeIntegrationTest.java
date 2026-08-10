package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.github.tomakehurst.wiremock.client.WireMock;

import jakarta.servlet.http.Cookie;

/**
 * AC-16 — teto de vida da sessão do IdP, ancorado no <b>instante de autenticação</b>.
 *
 * <p>O teto é exercitado por <b>override de propriedade</b>
 * ({@code security.session.max-lifetime=1ms}), nunca por {@code Thread.sleep}: espera cronometrada é
 * antipadrão declarado no projeto e produziria um teste lento e flaky por construção. Com o teto em
 * 1ms, qualquer sessão já nasce vencida no authorize seguinte — e é isso que se quer provar.
 *
 * <p>O caso complementar (AC-17: dentro do teto, emite normalmente) vive em
 * {@code AuthorizeRevalidationIntegrationTest}, sob o default de 8h. O par é necessário: um teto que
 * derruba tudo passaria sozinho no teste de baixo.
 */
@TestPropertySource(properties = "security.session.max-lifetime=1ms")
class SessionMaxLifetimeIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CLIENT_ID = "gateway-client";
    private static final String REDIRECT_URI = "http://localhost:8081/login/oauth2/code/gateway-client";
    private static final String SENHA = "Senha123";
    private static final String SENHA_HASH = new BCryptPasswordEncoder().encode(SENHA);
    private static final String CODE_CHALLENGE = gerarCodeChallenge();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    @Test
    @DisplayName("AC-16: sessão além do teto é invalidada no authorize, sem emitir código")
    void deveInvalidarSessao_quandoAlemDoTetoDeVida() throws Exception {
        String email = "vencida@test.com";
        stubTitularAtivo(email);

        // Login normal: o titular está íntegro em tudo — só o teto o alcança.
        MvcResult anonimo = mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie sessao = anonimo.getResponse().getCookie("AUTHSESSION");
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email).param("password", SENHA)
                        .cookie(sessao).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie autenticada = login.getResponse().getCookie("AUTHSESSION") != null
                ? login.getResponse().getCookie("AUTHSESSION")
                : sessao;
        String sessionId = new String(Base64.getDecoder().decode(autenticada.getValue()),
                StandardCharsets.UTF_8);

        MvcResult autorizacao = mockMvc.perform(authorizeRequest().cookie(autenticada))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = autorizacao.getResponse().getRedirectedUrl();

        assertEquals("/login", location);
        assertNull(UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code"),
                "nenhum authorization_code pode ser emitido para sessão vencida");
        assertFalse(sessionRepository.findById(sessionId) != null,
                "a sessão vencida tem de ser destruída no Redis");
    }

    private MockHttpServletRequestBuilder authorizeRequest() {
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "openid profile")
                .queryParam("state", "estado-teste")
                .queryParam("code_challenge", CODE_CHALLENGE)
                .queryParam("code_challenge_method", "S256");
    }

    private void stubTitularAtivo(String email) {
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "user-id-vencida",
                          "email": "%s",
                          "passwordHash": "%s",
                          "active": true,
                          "roles": ["USER"]
                        }
                        """.formatted(email, SENHA_HASH))));
    }

    private static String gerarCodeChallenge() {
        try {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
