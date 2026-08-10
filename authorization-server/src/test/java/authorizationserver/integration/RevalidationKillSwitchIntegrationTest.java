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
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.github.tomakehurst.wiremock.client.WireMock;

import authorizationserver.filter.AuthorizationEndpointRevalidationFilter;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

/**
 * Reversão operacional do ADR-025 ({@code security.session.revalidation.enabled=false}).
 *
 * <p><b>Por que isto merece um contexto próprio.</b> O raio de um defeito no filtro de re-derivação
 * é <i>"ninguém consegue logar"</i> (R-01, P0), e o kill switch é o <b>único</b> rollback que não
 * exige rebuild. Um kill switch que não desliga é pior que nenhum: no dia do incidente, quem o
 * acionar vai acreditar que agiu e continuará com o sistema quebrado.
 *
 * <p>O teste unitário do filtro cobre a <i>lógica</i> do toggle, mas não o <b>fio</b>: o
 * {@code @Value("${security.session.revalidation.enabled:true}")} vive no {@code SecurityConfig}, e
 * um nome de propriedade errado ali cai silenciosamente no default {@code true} — a propriedade
 * nasceria documentada e inerte, exatamente o modo de falha do
 * {@code MANAGEMENT_TRACING_SAMPLING_PROBABILITY} que o projeto já registrou.
 *
 * <p><b>O comportamento asserido aqui é o VULNERÁVEL, de propósito</b> — com o switch em
 * {@code false} o sistema volta ao estado do incidente de 2026-08-07. Este é o único lugar do
 * repositório onde isso é aceitável, e é o que prova que o botão faz alguma coisa.
 */
@TestPropertySource(properties = "security.session.revalidation.enabled=false")
class RevalidationKillSwitchIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CLIENT_ID = "gateway-client";
    private static final String REDIRECT_URI = "http://localhost:8081/login/oauth2/code/gateway-client";
    private static final String SENHA = "Senha123";
    private static final String SENHA_HASH = new BCryptPasswordEncoder().encode(SENHA);
    private static final String CODE_CHALLENGE = gerarCodeChallenge();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    @DisplayName("Kill switch: com a re-derivação desligada, titular eliminado volta a emitir código")
    void deveEmitirCodigoParaTitularEliminado_quandoRevalidacaoDesligada() throws Exception {
        String email = "kill.switch@test.com";
        stubTitular(email, "user-id-kill-switch");
        Cookie sessao = autenticar(email);
        String sessionId = idDe(sessao);

        // Titular eliminado: com o switch LIGADO isto seria 302 /login + sessão destruída (AC-02).
        userServiceMock.resetAll();
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .willReturn(WireMock.aResponse().withStatus(404)));

        MvcResult autorizacao = mockMvc.perform(authorizeRequest().cookie(sessao))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = autorizacao.getResponse().getRedirectedUrl();

        assertTrue(location.startsWith(REDIRECT_URI),
                "com o switch desligado o comportamento vulnerável tem de voltar: " + location);
        assertNotNull(UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code"),
                "o kill switch não desligou a re-derivação — o rollback do R-01 não funciona: " + location);
        assertNotNull(sessionRepository.findById(sessionId), "a sessão não pode ser destruída");
        userServiceMock.verify(0, WireMock.getRequestedFor(
                WireMock.urlPathMatching("/internal/users/email/.*")));
    }

    @Test
    @DisplayName("Kill switch: desligado, o filtro CONTINUA na chain — o toggle é no-op, não desmontagem")
    void deveManterOFiltroNaChain_mesmoDesligado() throws Exception {
        // A guarda estrutural do AC-34 não pode depender de propriedade: se desligar o toggle também
        // removesse o filtro da chain, o AuthorizationChainStructureIntegrationTest passaria a medir
        // configuração em vez de wiring, e um erro de posição voltaria a passar despercebido.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setServletPath("/oauth2/authorize");

        List<Filter> filtros = filterChainProxy.getFilterChains().stream()
                .filter(chain -> chain.matches(request))
                .findFirst()
                .map(SecurityFilterChain::getFilters)
                .orElseThrow();

        assertTrue(filtros.stream().anyMatch(AuthorizationEndpointRevalidationFilter.class::isInstance),
                "o filtro tem de permanecer na chain mesmo desabilitado");
    }

    private Cookie autenticar(String email) throws Exception {
        MvcResult anonimo = mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie sessao = anonimo.getResponse().getCookie("AUTHSESSION");

        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email).param("password", SENHA)
                        .cookie(sessao).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie nova = login.getResponse().getCookie("AUTHSESSION");
        return nova != null ? nova : sessao;
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

    private void stubTitular(String email, String userId) {
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "%s",
                          "email": "%s",
                          "passwordHash": "%s",
                          "active": true,
                          "roles": ["USER"]
                        }
                        """.formatted(userId, email, SENHA_HASH))));
    }

    private String idDe(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
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
