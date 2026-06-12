package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Lockout anti-brute-force (C19) ponta a ponta, com os beans reais
 * ({@code LoginAttemptService} + {@code LoginAttemptListener} + {@code AuthorizationService})
 * e o contador no Redis do Testcontainers (chave {@code login_fail:sha256(emailLower|ip)}).
 *
 * <p>Limite usado: {@code security.lockout.max-attempts=5} do application.yml de teste
 * (mesmo default de produção). O IP do par (conta, IP) vem de {@code getRemoteAddr()}
 * ({@code ClientIpResolver}); no MockMvc o default é 127.0.0.1 e os testes de isolamento
 * por IP usam {@code setRemoteAddr(...)} via RequestPostProcessor.
 *
 * <p>O {@code flushDb()} da classe base zera os contadores entre testes — falhas de um
 * teste não bloqueiam o par (conta, IP) de outro.
 */
class LoginLockoutIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String SENHA = "Senha123";
    private static final String SENHA_ERRADA = "SenhaErrada999";
    private static final String SENHA_HASH = new BCryptPasswordEncoder().encode(SENHA);
    /** security.lockout.max-attempts do application.yml de teste. */
    private static final int MAX_TENTATIVAS = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void deveBloquearLoginComSenhaCorreta_quandoLimiteDeFalhasAtingidoNoParContaIp() throws Exception {
        // ARRANGE — usuário ativo no "user-service" (WireMock)
        String email = "lockout.bloqueio@test.com";
        stubUsuarioAtivo("bloqueio", email);

        // ACT — 5 falhas de senha no mesmo par (conta, IP), depois a senha CORRETA na 6ª
        String redirectFalha = null;
        for (int i = 0; i < MAX_TENTATIVAS; i++) {
            redirectFalha = login(email, SENHA_ERRADA);
        }
        String redirectBloqueado = login(email, SENHA);

        // ASSERT — o login NÃO sucede (LockedException antes da checagem de senha) e o
        // redirect é IDÊNTICO ao da falha de senha: nenhuma distinção vaza na resposta HTTP
        assertEquals("/login?error", redirectBloqueado);
        assertEquals(redirectFalha, redirectBloqueado,
                "bloqueio e senha errada devem produzir a mesma resposta (mensagem genérica)");
    }

    @Test
    void deveZerarContadorDeFalhas_quandoLoginBemSucedidoAntesDoLimite() throws Exception {
        // ARRANGE
        String email = "lockout.reset@test.com";
        stubUsuarioAtivo("reset", email);

        // ACT — falhas abaixo do limite, depois um sucesso
        login(email, SENHA_ERRADA);
        login(email, SENHA_ERRADA);
        Set<String> chavesAntesDoSucesso = redisTemplate.keys("login_fail:*");
        String redirectSucesso = login(email, SENHA);
        Set<String> chavesAposSucesso = redisTemplate.keys("login_fail:*");

        // ASSERT — o contador existia após as falhas e foi removido no sucesso
        assertEquals(1, chavesAntesDoSucesso.size(),
                "as falhas devem criar exatamente um contador para o par (conta, IP)");
        assertEquals("/", redirectSucesso, "login antes do limite deve suceder");
        assertTrue(chavesAposSucesso.isEmpty(), "o sucesso deve remover o contador do Redis");
    }

    @Test
    void naoDeveBloquearOutraConta_quandoLimiteDeFalhasEsgotadoEmUmEmail() throws Exception {
        // ARRANGE — duas contas distintas no mesmo IP
        String emailBloqueado = "lockout.alice@test.com";
        String emailLivre = "lockout.bob@test.com";
        stubUsuarioAtivo("alice", emailBloqueado);
        stubUsuarioAtivo("bob", emailLivre);

        // ACT — esgota o limite de alice; bob tenta com a senha correta
        for (int i = 0; i < MAX_TENTATIVAS; i++) {
            login(emailBloqueado, SENHA_ERRADA);
        }
        String redirectBloqueado = login(emailBloqueado, SENHA);
        String redirectLivre = login(emailLivre, SENHA);

        // ASSERT — alice bloqueada; as falhas dela não contaminam o par de bob
        assertEquals("/login?error", redirectBloqueado);
        assertEquals("/", redirectLivre, "falhas de uma conta não devem bloquear outra");
    }

    @Test
    void naoDeveBloquearMesmaConta_quandoFalhasVieramDeOutroIp() throws Exception {
        // ARRANGE — mesma conta, IPs distintos (chave do lockout é o par conta+IP)
        String email = "lockout.carol@test.com";
        stubUsuarioAtivo("carol", email);

        // ACT — esgota o limite a partir de um IP; senha correta a partir de outro
        for (int i = 0; i < MAX_TENTATIVAS; i++) {
            loginDeIp(email, SENHA_ERRADA, "10.0.0.1");
        }
        String redirectIpBloqueado = loginDeIp(email, SENHA, "10.0.0.1");
        String redirectOutroIp = loginDeIp(email, SENHA, "10.0.0.2");

        // ASSERT — bloqueio restrito ao par (conta, IP) que falhou
        assertEquals("/login?error", redirectIpBloqueado);
        assertEquals("/", redirectOutroIp, "falhas de um IP não devem bloquear a conta em outro IP");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Stub do canal Feign para um e-mail específico (fragmento único do local-part). */
    private void stubUsuarioAtivo(String fragmento, String email) {
        userServiceMock.stubFor(
                WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*" + fragmento + ".*"))
                        .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                        .willReturn(WireMock.okJson("""
                                {
                                  "id": "id-%s",
                                  "email": "%s",
                                  "passwordHash": "%s",
                                  "active": true,
                                  "roles": ["USER"]
                                }
                                """.formatted(fragmento, email, SENHA_HASH))));
    }

    /** Submete o form login e devolve o Location do redirect ("/" sucesso, "/login?error" falha). */
    private String login(String email, String senha) throws Exception {
        return executarLogin(formLogin(email, senha));
    }

    /** Igual a {@link #login}, fixando o remoteAddr lido pelo {@code ClientIpResolver}. */
    private String loginDeIp(String email, String senha, String ip) throws Exception {
        return executarLogin(formLogin(email, senha)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    private MockHttpServletRequestBuilder formLogin(String email, String senha) {
        return post("/login")
                .param("username", email)
                .param("password", senha)
                .with(csrf());
    }

    private String executarLogin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }
}
