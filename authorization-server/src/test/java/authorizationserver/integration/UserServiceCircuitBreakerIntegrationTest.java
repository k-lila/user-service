package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Circuit breaker C7 na chamada Feign ao user-service, com o pipeline real
 * (Feign + Resilience4j + {@code UserClientFallbackFactory}) e o WireMock simulando
 * o downstream indisponível (500 e delay acima do timeout).
 *
 * <p>Configuração de TESTE no application.yml ({@code resilience4j.*.instances.user-service}):
 * janela 2 / mínimo 2 chamadas / threshold 50% — duas falhas consecutivas abrem o circuito;
 * {@code waitDurationInOpenState=60s} o mantém aberto durante o teste (a classe base reseta
 * o registry entre testes); TimeLimiter de 2s (produção usa 3s).
 *
 * <p>O fallback lança {@code UserServiceUnavailableException} (ADR-021), que estende
 * {@code InternalAuthenticationServiceException} e o {@code AuthorizationService} propaga sem
 * reembrulhar → na borda HTTP o form login redireciona para {@code /login?error} (falha
 * controlada, sem 5xx e sem travar no delay do downstream). Antes do ADR-021 o fallback lançava
 * {@code UsernameNotFoundException}, que o {@code DaoAuthenticationProvider} convertia em
 * {@code BadCredentialsException} — e o {@code LoginAttemptListener} contava a falha, fazendo um
 * outage bloquear contas legítimas por 15 min. É o que
 * {@link #naoDeveBloquearConta_apos5FalhasDuranteOutage()} cobre.
 */
class UserServiceCircuitBreakerIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String SENHA = "Senha123";
    private static final String SENHA_HASH = new BCryptPasswordEncoder().encode(SENHA);
    /** resilience4j.timelimiter.instances.user-service.timeoutDuration do yml de teste. */
    private static final Duration TIMEOUT_CONFIGURADO = Duration.ofSeconds(2);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private authorizationserver.services.LoginAttemptService loginAttempts;

    @Test
    void deveFalharLoginDeFormaControlada_quandoUserServiceRetorna500() throws Exception {
        // ARRANGE — downstream respondendo 500
        stubUserServiceCom500();

        // ACT
        String redirect = login("cb.erro500@test.com");
        int chamadas = contarChamadasAoUserService();

        // ASSERT — fallback (UsernameNotFoundException) vira falha controlada de login,
        // com exatamente uma chamada ao downstream (sem retry, C14: uma chamada por login)
        assertEquals("/login?error", redirect);
        assertEquals(1, chamadas);
    }

    @Test
    void deveFalharSemEsperarDelayDoDownstream_quandoUserServiceExcedeTimeout() throws Exception {
        // ARRANGE — resposta VÁLIDA, porém atrasada (5s) além do timeoutDuration (2s)
        String email = "cb.timeout@test.com";
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "id-timeout",
                          "email": "%s",
                          "passwordHash": "%s",
                          "active": true,
                          "roles": ["USER"]
                        }
                        """.formatted(email, SENHA_HASH))
                        .withFixedDelay(5000)));

        // ACT
        long inicio = System.nanoTime();
        String redirect = login(email);
        Duration duracao = Duration.ofNanos(System.nanoTime() - inicio);

        // ASSERT — o TimeLimiter corta em 2s e o fallback falha o login de forma controlada,
        // sem esperar os 5s do downstream (folga de 2,5s sobre o timeout para absorver warm-up)
        assertEquals("/login?error", redirect);
        assertTrue(duracao.compareTo(Duration.ofMillis(4500)) < 0,
                "login deve falhar no timeout do TimeLimiter, não no delay do downstream; durou " + duracao);
    }

    @Test
    void naoDeveChamarUserService_quandoCircuitoAberto() throws Exception {
        // ARRANGE — duas falhas consecutivas (janela 2, threshold 50%) abrem o circuito
        stubUserServiceCom500();
        login("cb.abre1@test.com");
        login("cb.abre2@test.com");
        int chamadasAposAbrir = contarChamadasAoUserService();

        // ACT — com o circuito aberto, novas tentativas caem direto no fallback
        long inicio = System.nanoTime();
        String redirect = login("cb.aberto1@test.com");
        Duration duracao = Duration.ofNanos(System.nanoTime() - inicio);
        login("cb.aberto2@test.com");
        int chamadasFinais = contarChamadasAoUserService();

        // ASSERT — falha controlada e imediata (abaixo do timeout de 2s), sem nenhuma
        // requisição nova chegando ao WireMock
        assertEquals(2, chamadasAposAbrir, "as duas falhas que abrem o circuito chegam ao downstream");
        assertEquals("/login?error", redirect);
        assertEquals(chamadasAposAbrir, chamadasFinais,
                "com o circuito aberto, nenhuma chamada nova deve chegar ao user-service");
        assertTrue(duracao.compareTo(TIMEOUT_CONFIGURADO) < 0,
                "com o circuito aberto o fallback deve responder antes do timeout; durou " + duracao);
    }

    /**
     * ADR-021 — o teste de cruzamento lockout × circuit breaker: a prova do bug e do fix.
     *
     * <p>Antes do fix este teste FALHAVA. O fallback lançava {@code UsernameNotFoundException},
     * o {@code DaoAuthenticationProvider} a convertia em {@code BadCredentialsException}, o
     * publisher emitia {@code AuthenticationFailureBadCredentialsEvent} e o
     * {@code LoginAttemptListener} incrementava — cinco tentativas durante um outage bloqueavam
     * a conta por 15 min (max-attempts=5 no yml de teste). Um incidente de infraestrutura virava
     * negação de serviço para o usuário legítimo.
     *
     * <p>Assere sobre o {@code LoginAttemptService} diretamente, e não sobre um login bem-sucedido
     * depois: o circuito abre com 2 falhas e {@code waitDurationInOpenState=60s} o mantém aberto
     * até o fim do teste, então não há como restaurar o downstream e autenticar aqui.
     */
    @Test
    void naoDeveBloquearConta_apos5FalhasDuranteOutage() throws Exception {
        // ARRANGE — downstream fora do ar durante toda a sequência
        String email = "cb.outage@test.com";
        stubUserServiceCom500();

        // ACT — 6 tentativas, uma acima do max-attempts=5 do yml de teste
        for (int i = 0; i < 6; i++) {
            assertEquals("/login?error", login(email), "tentativa " + (i + 1) + " deve falhar controlada");
        }

        // ASSERT — nenhuma delas pode ter contado como credencial inválida.
        // O IP é o do MockMvc (127.0.0.1), o mesmo que o ClientIpResolver enxerga sem o header
        // confiável — é o par (conta, IP) que o lockout particiona.
        assertFalse(loginAttempts.isBlocked(email, "127.0.0.1"),
                "indisponibilidade do user-service não pode alimentar o lockout (ADR-021)");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubUserServiceCom500() {
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .willReturn(WireMock.serverError()));
    }

    private int contarChamadasAoUserService() {
        return userServiceMock.findAll(
                WireMock.getRequestedFor(WireMock.urlPathMatching("/internal/users/email/.*"))).size();
    }

    /** Submete o form login e devolve o Location do redirect. */
    private String login(String email) throws Exception {
        return mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", SENHA)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }
}
