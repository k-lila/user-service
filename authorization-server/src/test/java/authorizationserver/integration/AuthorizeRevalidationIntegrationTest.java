package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.github.tomakehurst.wiremock.client.WireMock;

import authorizationserver.session.AuthenticationInstantAttribute;

import jakarta.servlet.http.Cookie;

/**
 * Re-derivação do estado do titular na emissão (ADR-025) exercitada pela
 * {@code SecurityFilterChain} REAL — MockMvc sobre o contexto completo, com Postgres e Redis de
 * Testcontainers e o user-service dublado por WireMock. É a diferença que importa: um filtro
 * instanciado isoladamente prova comportamento, não prova que ele roda.
 *
 * <p>Cada cenário nega uma linha da evidência do incidente de 2026-08-07 (nove
 * {@code authorization_code} emitidos após um hard-delete, zero autenticações, sessão do IdP viva 33
 * min depois da eliminação).
 */
class AuthorizeRevalidationIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CLIENT_ID = "gateway-client";
    private static final String REDIRECT_URI = "http://localhost:8081/login/oauth2/code/gateway-client";
    private static final String SENHA = "Senha123";
    private static final String SENHA_HASH = new BCryptPasswordEncoder().encode(SENHA);
    /** Destino do LoginUrlAuthenticationEntryPoint (SecurityConfig) sob MockMvc — relativo. */
    private static final String LOGIN_REDIRECT = "/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    /**
     * Eventos de autenticação publicados no contexto, para as asserções de AC-32/AC-38.
     *
     * <p>Estático e registrado uma única vez: o contexto Spring é compartilhado entre os testes, e
     * registrar um listener por método os acumularia. O listener aceita {@link ApplicationEvent} e
     * filtra por {@code instanceof} de propósito — um lambda tipado em
     * {@code ApplicationListener<AbstractAuthenticationEvent>} perde o genérico em runtime e o
     * multicaster lhe entregaria eventos de qualquer tipo.
     */
    private static final List<AbstractAuthenticationEvent> EVENTOS_DE_AUTENTICACAO =
            new CopyOnWriteArrayList<>();

    private static boolean listenerRegistrado = false;

    @BeforeEach
    void registrarListenerDeEventosDeAutenticacao() {
        if (!listenerRegistrado) {
            applicationContext.addApplicationListener(new ApplicationListener<ApplicationEvent>() {
                @Override
                public void onApplicationEvent(ApplicationEvent event) {
                    if (event instanceof AbstractAuthenticationEvent autenticacao) {
                        EVENTOS_DE_AUTENTICACAO.add(autenticacao);
                    }
                }
            });
            listenerRegistrado = true;
        }
        EVENTOS_DE_AUTENTICACAO.clear();
    }

    // ── AC-01: o caminho feliz não regride ──────────────────────────────────────

    @Test
    @DisplayName("AC-01: sessão íntegra continua emitindo o authorization_code, pela chain real")
    void deveEmitirCodigo_quandoSessaoIntegra() throws Exception {
        String email = "integro@test.com";
        stubTitular(email, "user-id-integro", true, "USER");
        Sessao sessao = autenticar(email);

        MvcResult autorizacao = autorizar(sessao);
        String location = autorizacao.getResponse().getRedirectedUrl();

        assertTrue(location.startsWith(REDIRECT_URI), "esperado redirect ao redirect_uri: " + location);
        assertNotNull(parametro(location, "code"), "o código tem de ser emitido: " + location);
        assertNull(parametro(location, "error"), location);
        assertTrue(sessaoViva(sessao.id()), "a sessão íntegra não pode ser destruída");
    }

    @Test
    @DisplayName("AC-01: a mesma sessão íntegra emite N vezes (nenhuma auto-invalidação latente)")
    void deveEmitirCodigoRepetidamente_quandoSessaoIntegra() throws Exception {
        String email = "integro.repetido@test.com";
        stubTitular(email, "user-id-repetido", true, "USER");
        Sessao sessao = autenticar(email);

        for (int i = 0; i < 3; i++) {
            String location = autorizar(sessao).getResponse().getRedirectedUrl();
            assertNotNull(parametro(location, "code"), "emissão " + i + ": " + location);
        }
        assertTrue(sessaoViva(sessao.id()));
    }

    // ── AC-02: titular eliminado ────────────────────────────────────────────────

    @Test
    @DisplayName("AC-02: hard-delete — sessão morre no primeiro contato, zero linhas novas em oauth2_authorization")
    void deveInvalidarSessao_quandoTitularEliminado() throws Exception {
        String email = "eliminado@test.com";
        stubTitular(email, "user-id-eliminado", true, "USER");
        Sessao sessao = autenticar(email);

        // O titular exerce o direito de eliminação: o documento some do Mongo e o canal interno
        // passa a responder 404 (resolvido em UsernameNotFoundException pelo UserClientFallbackFactory).
        long linhasAntes = linhasDeAutorizacao(email);
        stubTitularInexistente(email);

        MvcResult autorizacao = autorizar(sessao);

        assertEquals(LOGIN_REDIRECT, autorizacao.getResponse().getRedirectedUrl());
        assertFalse(sessaoViva(sessao.id()),
                "a chave authserver:session:sessions:{id} tem de sumir do Redis — no incidente ela viveu 33 min");
        assertEquals(linhasAntes, linhasDeAutorizacao(email),
                "NENHUMA linha nova em oauth2_authorization para o principal eliminado (no incidente foram nove)");
    }

    @Test
    @DisplayName("AC-03: soft-delete (active=false) barra pelo mesmo gate que o form login aplica")
    void deveInvalidarSessao_quandoTitularInativo() throws Exception {
        String email = "inativo@test.com";
        stubTitular(email, "user-id-inativo", true, "USER");
        Sessao sessao = autenticar(email);

        // O user-service devolve 404 para titular inativo — mesmo caminho do inexistente.
        long linhasAntes = linhasDeAutorizacao(email);
        stubTitularInexistente(email);

        MvcResult autorizacao = autorizar(sessao);

        assertEquals(LOGIN_REDIRECT, autorizacao.getResponse().getRedirectedUrl());
        assertFalse(sessaoViva(sessao.id()));
        assertEquals(linhasAntes, linhasDeAutorizacao(email));
    }

    // ── AC-04: gate de e-mail verificado ────────────────────────────────────────

    @Test
    @DisplayName("AC-04: emailVerified=false fora da carência de 24h invalida a sessão (ADR-015 no SSO silencioso)")
    void deveInvalidarSessao_quandoEmailNaoVerificadoForaDaCarencia() throws Exception {
        String email = "nao.verificado@test.com";
        // Sessão criada enquanto o titular ainda estava dentro da carência.
        stubTitular(email, "user-id-nv", true, "USER");
        Sessao sessao = autenticar(email);

        // Carência vencida: registrationDate antiga + emailVerified=false → enabled=false.
        userServiceMock.resetAll();
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "user-id-nv",
                          "email": "%s",
                          "passwordHash": "%s",
                          "active": true,
                          "roles": ["USER"],
                          "emailVerified": false,
                          "registrationDate": "%s"
                        }
                        """.formatted(email, SENHA_HASH, Instant.now().minusSeconds(72 * 3600)))));

        MvcResult autorizacao = autorizar(sessao);

        assertEquals(LOGIN_REDIRECT, autorizacao.getResponse().getRedirectedUrl());
        assertFalse(sessaoViva(sessao.id()));
    }

    // ── AC-05 / AC-07: divergência de authorities ───────────────────────────────

    @Test
    @DisplayName("AC-05: ADMIN revogado — nenhum token com roles:[ADMIN] nem permissions users.delete")
    void deveInvalidarSessao_quandoRoleRevogada() throws Exception {
        String email = "ex.admin@test.com";
        stubTitular(email, "user-id-ex-admin", true, "ADMIN", "USER");
        Sessao sessao = autenticar(email);

        // PATCH /v1/admin/users/{id}/roles removeu o ADMIN. Sem a re-derivação, cada autorização
        // cunharia token novo com ADMIN — e RENOVARIA a sessão, tornando o privilégio revogado
        // auto-renovável indefinidamente.
        stubTitular(email, "user-id-ex-admin", true, "USER");

        MvcResult autorizacao = autorizar(sessao);
        String location = autorizacao.getResponse().getRedirectedUrl();

        assertEquals(LOGIN_REDIRECT, location);
        assertNull(parametro(location, "code"), "nenhum código pode ser emitido");
        assertFalse(sessaoViva(sessao.id()));
    }

    @Test
    @DisplayName("AC-07: concessão de role também passa pelo re-login (consequência aceita)")
    void deveInvalidarSessao_quandoRoleConcedida() throws Exception {
        String email = "promovido@test.com";
        stubTitular(email, "user-id-promovido", true, "USER");
        Sessao sessao = autenticar(email);

        stubTitular(email, "user-id-promovido", true, "ADMIN", "USER");

        assertEquals(LOGIN_REDIRECT, autorizar(sessao).getResponse().getRedirectedUrl());
        assertFalse(sessaoViva(sessao.id()));

        // E após o re-login o estado novo vale: é o re-login que re-deriva, não o filtro.
        Sessao nova = autenticar(email);
        String location = autorizar(nova).getResponse().getRedirectedUrl();
        assertNotNull(parametro(location, "code"), location);
    }

    @Test
    @DisplayName("Restrição 1: só o userID diverge (mesmo e-mail, mesmas roles) → invalida também")
    void deveInvalidarSessao_quandoApenasUserIdDiverge() throws Exception {
        // Delete + re-registro com o mesmo e-mail: as roles coincidem, o documento é outro. Comparar
        // só ROLE_* cunharia um token cujo claim userID aponta para documento inexistente.
        String email = "recadastrado@test.com";
        stubTitular(email, "id-antigo", true, "USER");
        Sessao sessao = autenticar(email);

        stubTitular(email, "id-novo", true, "USER");

        assertEquals(LOGIN_REDIRECT, autorizar(sessao).getResponse().getRedirectedUrl());
        assertFalse(sessaoViva(sessao.id()));
    }

    // ── AC-10: degradação por epoch quando a fonte de verdade está fora ─────────

    @Test
    @DisplayName("AC-10: user-service fora + epoch posterior à autenticação → sessão invalidada")
    void deveDegradarParaOEpoch_quandoUserServiceFora() throws Exception {
        String email = "outage.revogado@test.com";
        String userId = "user-id-outage-revogado";
        stubTitular(email, userId, true, "USER");
        Sessao sessao = autenticar(email);

        // Revogação gravada pelo user-service ANTES de ele cair.
        redisTemplate.opsForValue().set("revoke:user:" + userId,
                Long.toString(System.currentTimeMillis() + 5000));
        stubUserServiceIndisponivel();

        assertEquals(LOGIN_REDIRECT, autorizar(sessao).getResponse().getRedirectedUrl());
        assertFalse(sessaoViva(sessao.id()));
    }

    @Test
    @DisplayName("AC-10/AC-11 [NEGATIVO]: user-service fora e SEM epoch → o código é emitido (fail-open)")
    void deveEmitirCodigo_quandoUserServiceForaESemEpoch() throws Exception {
        String email = "outage.ok@test.com";
        stubTitular(email, "user-id-outage-ok", true, "USER");
        Sessao sessao = autenticar(email);

        stubUserServiceIndisponivel();

        String location = autorizar(sessao).getResponse().getRedirectedUrl();

        assertNotNull(parametro(location, "code"),
                "um outage do user-service não pode derrubar o login de quem já está dentro: " + location);
        assertTrue(sessaoViva(sessao.id()));
    }

    // ── AC-14 / AC-35 / AC-38: instante de autenticação na sessão real ──────────

    @Test
    @DisplayName("AC-14/AC-35: o login grava o carimbo como Long, e o RedisSessionRepository real o serializa")
    void deveGravarCarimboSerializavelNaSessaoRedis() throws Exception {
        String email = "carimbo@test.com";
        stubTitular(email, "user-id-carimbo", true, "USER");
        long antes = System.currentTimeMillis();

        Sessao sessao = autenticar(email);

        // Lido do repositório REAL (Spring Session + Redis), não de um mock: um valor
        // não-serializável estouraria SerializationException no save e quebraria TODO login.
        Session persistida = sessionRepository.findById(sessao.id());
        assertNotNull(persistida, "a sessão autenticada tem de estar no Redis");
        Object carimbo = persistida.getAttribute(AuthenticationInstantAttribute.NAME);
        assertInstanceOf(Long.class, carimbo, "o atributo tem de ser Long de epoch millis");
        assertTrue((Long) carimbo >= antes, "o carimbo é o instante do login");
    }

    @Test
    @DisplayName("AC-38 [NEGATIVO]: N autorizações não re-carimbam o instante de autenticação")
    void deveNaoRecarimbarInstante_aposVariasAutorizacoes() throws Exception {
        String email = "sem.recarimbo@test.com";
        stubTitular(email, "user-id-sem-recarimbo", true, "USER");
        Sessao sessao = autenticar(email);
        Long t0 = (Long) sessionRepository.findById(sessao.id())
                .getAttribute(AuthenticationInstantAttribute.NAME);
        assertNotNull(t0);

        for (int i = 0; i < 3; i++) {
            assertNotNull(parametro(autorizar(sessao).getResponse().getRedirectedUrl(), "code"));
        }

        Long depois = (Long) sessionRepository.findById(sessao.id())
                .getAttribute(AuthenticationInstantAttribute.NAME);
        assertEquals(t0, depois,
                "re-carimbar T0 esvaziaria o teto de vida E derrotaria a degradação por epoch "
                        + "(isRevoked é epoch > instante), reencarnando o bug do incidente no fallback");
    }

    // ── AC-32: o lockout não é tocado pela re-derivação ─────────────────────────

    @Test
    @DisplayName("AC-32 [NEGATIVO]: N autorizações não ZERAM nem incrementam o contador de lockout")
    void deveNaoTocarNoContadorDeLockout() throws Exception {
        String email = "lockout.intacto@test.com";
        stubTitular(email, "user-id-lockout", true, "USER");
        Sessao sessao = autenticar(email);

        // Um terceiro que só conhece o e-mail acumula falhas no par (conta, IP).
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/login")
                    .param("username", email).param("password", "SenhaErrada999").with(csrf()));
        }
        String chave = chaveDeLockout();
        String antes = redisTemplate.opsForValue().get(chave);
        assertNotNull(antes, "o cenário exige contador > 0; chave de lockout não encontrada");

        for (int i = 0; i < 3; i++) {
            autorizar(sessao);
        }

        assertEquals(antes, redisTemplate.opsForValue().get(chave),
                "a re-derivação via ProviderManager ZERARIA o contador (loginSucceeded) — bypass do "
                        + "lockout do ADR-010 sem prova de senha");
    }

    @Test
    @DisplayName("AC-32 [NEGATIVO]: 404 recorrente (titular eliminado) não incrementa o contador de lockout")
    void deveNaoIncrementarContadorDeLockout_quandoTitularEliminadoEm404Recorrente() throws Exception {
        // A metade do AC-32 que o teste acima não cobre. O eixo aqui é o INCREMENTO: pelo form login
        // o 404 vira BadCredentialsException e CONTA no lockout (atrito anti-enumeração, ADR-021) —
        // é comportamento desejado ali. Na re-derivação NÃO pode contar: um titular eliminado que
        // deixe a aba aberta bateria as 5 falhas sozinho, e o par (conta, IP) ficaria bloqueado por
        // 15 min sem ninguém ter errado senha alguma.
        //
        // A sessão morre no primeiro contato, então "recorrente" exige N sessões abertas ANTES da
        // eliminação — que é exatamente o cenário do incidente (aba aberta, SSO silencioso repetido).
        String email = "eliminado.recorrente@test.com";
        stubTitular(email, "user-id-eliminado-rec", true, "USER");
        List<Sessao> sessoes = List.of(autenticar(email), autenticar(email), autenticar(email));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/login")
                    .param("username", email).param("password", "SenhaErrada999").with(csrf()));
        }
        String chave = chaveDeLockout();
        String antes = redisTemplate.opsForValue().get(chave);
        assertEquals("3", antes, "o cenário exige contador = 3 antes das autorizações");

        stubTitularInexistente(email);
        for (Sessao sessao : sessoes) {
            assertEquals(LOGIN_REDIRECT, autorizar(sessao).getResponse().getRedirectedUrl());
        }

        assertEquals(antes, redisTemplate.opsForValue().get(chave),
                "o 404 da re-derivação não pode alimentar o lockout — só o do form login conta");
        assertEquals(1, redisTemplate.keys("login_fail:*").size(),
                "nenhuma chave de lockout nova pode nascer da re-derivação");
    }

    @Test
    @DisplayName("AC-32/AC-38 [NEGATIVO]: nenhum evento do authorize carrega UsernamePasswordAuthenticationToken")
    void deveNaoPublicarEventoDeFormLogin_duranteAutorizacao() throws Exception {
        // ATENÇÃO — A LETRA DE AC-32 ESTÁ ERRADA, E ESTE TESTE DOCUMENTA POR QUÊ.
        //
        // A spec pede "NENHUM AuthenticationSuccessEvent é publicado" no authorize. Isso é FALSO no
        // sistema, e sempre foi: o próprio Spring Authorization Server autentica o
        // OAuth2AuthorizationCodeRequestAuthenticationToken por um ProviderManager e publica um
        // AuthenticationSuccessEvent a CADA autorização bem-sucedida — antes e depois do ADR-025.
        // Medido aqui: 3 autorizações → 3 eventos.
        //
        // O que de fato protege o lockout (B-4) são DUAS coisas, não uma:
        //   (1) a re-derivação chamar UserDetailsService direto, sem AuthenticationManager — que é o
        //       que o ADR-025 registra; e
        //   (2) o guard `instanceof UsernamePasswordAuthenticationToken` em LoginAttemptListener e em
        //       AuthenticationInstantListener — que o ADR-025 NÃO nomeia como controle, e sem o qual
        //       o evento do SAS acima já zeraria o contador (o nome resolvido é o do principal
        //       aninhado, o e-mail do titular) e re-carimbaria T0, reproduzindo o bypass do B-4 sem
        //       ninguém ter tocado no filtro.
        //
        // Logo a invariante testável não é "nenhum evento", é "nenhum evento cujo getAuthentication()
        // seja um UsernamePasswordAuthenticationToken" — que é exatamente a condição em que os dois
        // listeners agem.
        String email = "sem.eventos@test.com";
        stubTitular(email, "user-id-sem-eventos", true, "USER");
        Sessao sessao = autenticar(email);

        EVENTOS_DE_AUTENTICACAO.clear();
        for (int i = 0; i < 3; i++) {
            assertNotNull(parametro(autorizar(sessao).getResponse().getRedirectedUrl(), "code"));
        }

        List<AbstractAuthenticationEvent> deFormLogin = EVENTOS_DE_AUTENTICACAO.stream()
                .filter(e -> e.getAuthentication() instanceof UsernamePasswordAuthenticationToken)
                .toList();
        assertTrue(deFormLogin.isEmpty(),
                "evento de form login publicado no authorize: os listeners de lockout e de instante "
                        + "de autenticação agiriam, zerando o contador do ADR-010 sem prova de senha e "
                        + "re-carimbando T0. Eventos: " + deFormLogin);
    }

    @Test
    @DisplayName("AC-32/AC-38: controle — o form login PUBLICA o evento com UsernamePasswordAuthenticationToken")
    void devePublicarEventoDeFormLogin_noLogin() throws Exception {
        // Sem este controle, o teste anterior passaria também se o listener de captura nunca tivesse
        // sido registrado, ou se o tipo do token do form login tivesse mudado: garantia vazia com
        // build verde. É o par que dá sentido ao filtro por tipo usado acima.
        String email = "com.eventos@test.com";
        stubTitular(email, "user-id-com-eventos", true, "USER");

        EVENTOS_DE_AUTENTICACAO.clear();
        autenticar(email);

        assertTrue(EVENTOS_DE_AUTENTICACAO.stream()
                        .anyMatch(e -> e instanceof AuthenticationSuccessEvent
                                && e.getAuthentication() instanceof UsernamePasswordAuthenticationToken),
                "o form login tem de publicar AuthenticationSuccessEvent com "
                        + "UsernamePasswordAuthenticationToken: " + EVENTOS_DE_AUTENTICACAO);
    }

    // ── AC-09: lockout não derruba sessão viva, pela cadeia real ────────────────

    @Test
    @DisplayName("AC-09 [NEGATIVO]: conta BLOQUEADA de fato (5 falhas) não impede a emissão para sessão viva")
    void deveEmitirCodigo_quandoContaBloqueadaPeloLockout() throws Exception {
        // A versão unitária monta o UserDetails com accountNonLocked=false à mão. Aqui o bloqueio é
        // REAL: cinco falhas de senha levam o LoginAttemptService a bloquear o par (conta, IP), e o
        // AuthorizationService devolve accountNonLocked=false pelo caminho de produção. Se
        // accountNonLocked entrasse na comparação do filtro, qualquer um derrubaria a sessão de um
        // terceiro errando cinco senhas — o lockout do ADR-010 viraria DoS contra sessão viva.
        String email = "bloqueado@test.com";
        stubTitular(email, "user-id-bloqueado", true, "USER");
        Sessao sessao = autenticar(email);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login")
                    .param("username", email).param("password", "SenhaErrada999").with(csrf()));
        }
        assertEquals("5", redisTemplate.opsForValue().get(chaveDeLockout()),
                "o cenário exige o par (conta, IP) efetivamente bloqueado");
        // Prova de que o bloqueio está ATIVO: o login com a senha CERTA agora falha.
        MvcResult loginBloqueado = mockMvc.perform(post("/login")
                        .param("username", email).param("password", SENHA).with(csrf()))
                .andReturn();
        assertTrue(loginBloqueado.getResponse().getRedirectedUrl().contains("error"),
                "com o lockout ativo nem a senha correta passa: "
                        + loginBloqueado.getResponse().getRedirectedUrl());

        String location = autorizar(sessao).getResponse().getRedirectedUrl();

        assertNotNull(parametro(location, "code"),
                "a sessão viva de conta bloqueada tem de continuar emitindo: " + location);
        assertTrue(sessaoViva(sessao.id()));
    }

    // ── Premissa do recorte por prefixo (R-01, a pior regressão possível) ───────

    @Test
    @DisplayName("R-01: o login real produz authority FORA do domínio — é o que torna o recorte load-bearing")
    void deveHaverAuthorityDeFrameworkNaAutenticacaoReal() throws Exception {
        // CARACTERIZAÇÃO, não comportamento. O recorte por prefixo (ROLE_/USER_ID:) em
        // domainAuthorities() só é necessário porque o token do login carrega authorities que o
        // UserDetails re-derivado NÃO tem (o DaoAuthenticationProvider do Spring Security 7
        // acrescenta FACTOR_PASSWORD). Se um dia isso deixar de ser verdade, este teste falha e
        // alguém REVISA o recorte conscientemente — em vez de ele virar código morto, ou pior:
        // em vez de a única prova do recorte ser um teste unitário que constrói o FACTOR_PASSWORD
        // à mão e continuaria verde mesmo se o framework passasse a acrescentar OUTRA authority,
        // que é a que quebraria o login de verdade.
        String email = "authorities@test.com";
        stubTitular(email, "user-id-authorities", true, "USER");
        Sessao sessao = autenticar(email);

        Session persistida = sessionRepository.findById(sessao.id());
        assertNotNull(persistida);
        SecurityContext contexto = persistida.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(contexto, "o SecurityContext do login tem de estar na sessão do Redis");

        List<String> cruas = contexto.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
        List<String> foraDoDominio = cruas.stream()
                .filter(a -> !a.startsWith("ROLE_") && !a.startsWith("USER_ID:"))
                .toList();

        assertFalse(foraDoDominio.isEmpty(),
                "o token do login deveria trazer authority de framework (ex.: FACTOR_PASSWORD) ausente "
                        + "no UserDetails re-derivado — sem ela o recorte de domainAuthorities() perdeu "
                        + "o motivo de existir e precisa ser reavaliado. Authorities vistas: " + cruas);
        assertTrue(cruas.containsAll(List.of("ROLE_USER", "USER_ID:user-id-authorities")),
                "as authorities de domínio continuam presentes: " + cruas);
    }

    // ── AC-08 / R-10: volume do canal interno ───────────────────────────────────

    @Test
    @DisplayName("AC-08/R-10: exatamente UMA chamada ao canal interno por autorização, pelo e-mail da sessão")
    void deveChamarOCanalInternoUmaVezPorAutorizacao_comOEmailDaSessao() throws Exception {
        // DUAS coisas de uma vez, ambas hoje descobertas.
        //
        // (1) VOLUME. A ADR-025 assume esta aritmética para declarar o custo em auditoria (2 entradas
        //     READ_INTERNAL_CREDENTIAL por login normal + 1 por autorização) e é dela que sai a
        //     próxima revisão de AUDIT_LOG_RETENTION. Número assumido em prosa e não fixado por teste
        //     é número que muda sozinho.
        //
        // (2) O E-MAIL CONSULTADO. Todos os stubs desta classe casam por urlPathMatching(".*"), ou
        //     seja, respondem a QUALQUER e-mail — um filtro que re-derivasse pelo identificador
        //     errado (o userID, um literal, o principal de outra sessão) receberia o mesmo 200 e
        //     todos os cenários acima continuariam verdes. É o e-mail do principal da sessão que
        //     torna P-01 verdadeiro (trocar o e-mail → 404 → deslogado) e é o único identificador que
        //     o UserDetailsService aceita; deixá-lo sem asserção é deixar a premissa do desenho fora
        //     do alcance dos testes.
        String email = "volume@test.com";
        stubTitular(email, "user-id-volume", true, "USER");
        Sessao sessao = autenticar(email);

        userServiceMock.resetRequests();
        assertNotNull(parametro(autorizar(sessao).getResponse().getRedirectedUrl(), "code"));

        var requisicoes = userServiceMock.findAll(WireMock.getRequestedFor(
                WireMock.urlPathMatching("/internal/users/email/.*")));
        assertEquals(1, requisicoes.size(),
                "uma única leitura do canal interno por autorização: " + requisicoes);
        String consultado = URLDecoder.decode(requisicoes.get(0).getUrl(), StandardCharsets.UTF_8);
        assertTrue(consultado.endsWith("/internal/users/email/" + email),
                "a re-derivação tem de consultar o e-mail do principal da sessão: " + consultado);
    }

    // ── AC-27: após a invalidação, o re-login retoma o fluxo ────────────────────

    @Test
    @DisplayName("AC-27: após a invalidação, o re-login retoma o authorize original — sem laço")
    void deveRetomarFluxoOriginal_aposReLogin() throws Exception {
        String email = "retomada@test.com";
        stubTitular(email, "user-id-retomada", true, "ADMIN", "USER");
        Sessao sessao = autenticar(email);
        stubTitular(email, "user-id-retomada", true, "USER");

        // 1) authorize → invalidado, 302 /login, e o saved request vai para a sessão NOVA que o
        //    ExceptionTranslationFilter cria.
        MvcResult barrado = autorizar(sessao);
        assertEquals(LOGIN_REDIRECT, barrado.getResponse().getRedirectedUrl());
        Cookie novaSessao = barrado.getResponse().getCookie("AUTHSESSION");
        assertNotNull(novaSessao, "o request salvo exige uma sessão nova");

        // 2) re-login nessa sessão → o SavedRequestAwareAuthenticationSuccessHandler devolve ao
        //    /oauth2/authorize original, e não a "/" nem a uma tela morta.
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email).param("password", SENHA)
                        .cookie(novaSessao).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String destino = login.getResponse().getRedirectedUrl();
        assertTrue(destino.contains("/oauth2/authorize"),
                "o fluxo originalmente pedido tem de ser retomado, não descartado: " + destino);

        // 3) e o authorize agora emite — em NENHUMA hipótese há laço /login ↔ /oauth2/authorize.
        Cookie autenticada = sessaoAtualizada(login.getResponse(), novaSessao);
        String location = autorizar(new Sessao(autenticada, idDe(autenticada)))
                .getResponse().getRedirectedUrl();
        assertNotNull(parametro(location, "code"), location);
    }

    // ── AC-33: não-atuação no back-channel e no logout ──────────────────────────

    @Test
    @DisplayName("AC-33: /oauth2/token e /connect/logout não são tocados pelo filtro")
    void deveNaoAtuar_noTokenEndpointNemNoLogout() throws Exception {
        String email = "logout@test.com";
        stubTitular(email, "user-id-logout", true, "USER");
        Sessao sessao = autenticar(email);

        // Titular eliminado: se o filtro atuasse aqui, o /connect/logout destruiria a sessão antes
        // de o end_session_endpoint fazer o seu trabalho.
        stubTitularInexistente(email);
        mockMvc.perform(get("/connect/logout").cookie(sessao.cookie()));

        // O endpoint de logout pode encerrar a sessão por conta própria; o que se assere é que a
        // decisão NÃO veio do filtro de re-derivação — nenhuma chamada ao canal interno aconteceu.
        userServiceMock.verify(0, WireMock.getRequestedFor(
                WireMock.urlPathMatching("/internal/users/email/.*")));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private record Sessao(Cookie cookie, String id) {
    }

    /** Faz o fluxo completo authorize-anônimo → POST /login e devolve a sessão autenticada. */
    private Sessao autenticar(String email) throws Exception {
        MvcResult anonimo = mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie sessao = anonimo.getResponse().getCookie("AUTHSESSION");

        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email).param("password", SENHA)
                        .cookie(sessao).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie autenticada = sessaoAtualizada(login.getResponse(), sessao);
        return new Sessao(autenticada, idDe(autenticada));
    }

    private MvcResult autorizar(Sessao sessao) throws Exception {
        return mockMvc.perform(authorizeRequest().cookie(sessao.cookie()))
                .andExpect(status().is3xxRedirection()).andReturn();
    }

    private MockHttpServletRequestBuilder authorizeRequest() throws Exception {
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "openid profile")
                .queryParam("state", "estado-teste")
                .queryParam("code_challenge", CODE_CHALLENGE)
                .queryParam("code_challenge_method", "S256");
    }

    private static final String CODE_VERIFIER = gerarCodeVerifier();
    private static final String CODE_CHALLENGE = gerarCodeChallenge(CODE_VERIFIER);

    private void stubTitular(String email, String userId, boolean ativo, String... roles) {
        userServiceMock.resetAll();
        String rolesJson = String.join(",", List.of(roles).stream().map(r -> "\"" + r + "\"").toList());
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .withHeader("X-Internal-Token", WireMock.equalTo("test-internal-token"))
                .willReturn(WireMock.okJson("""
                        {
                          "id": "%s",
                          "email": "%s",
                          "passwordHash": "%s",
                          "active": %s,
                          "roles": [%s]
                        }
                        """.formatted(userId, email, SENHA_HASH, ativo, rolesJson))));
    }

    /** 404 do canal interno: titular eliminado OU inativo (o user-service não distingue). */
    private void stubTitularInexistente(String email) {
        userServiceMock.resetAll();
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .willReturn(WireMock.aResponse().withStatus(404)));
    }

    /** 503: indisponibilidade real → UserServiceUnavailableException (ADR-021). */
    private void stubUserServiceIndisponivel() {
        userServiceMock.resetAll();
        userServiceMock.stubFor(WireMock.get(WireMock.urlPathMatching("/internal/users/email/.*"))
                .willReturn(WireMock.aResponse().withStatus(503)));
    }

    private long linhasDeAutorizacao(String principal) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?", Long.class, principal);
        return total != null ? total : 0L;
    }

    private boolean sessaoViva(String sessionId) {
        return sessionRepository.findById(sessionId) != null;
    }

    /** O DefaultCookieSerializer codifica o id em Base64 no valor do cookie. */
    private String idDe(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    /** Chave do contador de lockout: o LoginAttemptService a deriva de sha256(email|ip). */
    private String chaveDeLockout() {
        var chaves = redisTemplate.keys("login_fail:*");
        assertFalse(chaves.isEmpty(), "nenhuma chave de lockout encontrada");
        return chaves.iterator().next();
    }

    private Cookie sessaoAtualizada(MockHttpServletResponse response, Cookie atual) {
        Cookie nova = response.getCookie("AUTHSESSION");
        return nova != null ? nova : atual;
    }

    private String parametro(String url, String nome) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(nome);
    }

    private static String gerarCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String gerarCodeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
