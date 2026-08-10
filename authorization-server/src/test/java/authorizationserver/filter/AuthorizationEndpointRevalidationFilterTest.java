package authorizationserver.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import authorizationserver.clients.UserServiceUnavailableException;
import authorizationserver.services.RevocationRefreshGuard;
import authorizationserver.session.AuthenticationInstantAttribute;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.servlet.http.HttpSession;

/**
 * Comportamento do filtro de re-derivação (ADR-025) isolado das dependências reais. A verificação
 * através da {@code SecurityFilterChain} de verdade — posição, chain, emissão do código — vive em
 * {@code AuthorizationChainStructureIntegrationTest} e {@code AuthorizeRevalidationIntegrationTest}:
 * um filtro correto em posição errada é no-op com build verde, e teste unitário não pega isso.
 */
class AuthorizationEndpointRevalidationFilterTest {

    private static final String ENDPOINT = "/oauth2/authorize";
    private static final String EMAIL = "titular@test.com";
    private static final String USER_ID = "6a761a6cbb6bdaf7004f8f27";

    private UserDetailsService userDetailsService;
    private RevocationRefreshGuard revocationGuard;
    private AuthorizationEndpointRevalidationFilter filter;
    private ListAppender<ILoggingEvent> logs;
    private Logger filterLogger;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(UserDetailsService.class);
        revocationGuard = mock(RevocationRefreshGuard.class);
        filter = novoFiltro(Duration.ofHours(8), true);

        filterLogger = (Logger) org.slf4j.LoggerFactory
                .getLogger(AuthorizationEndpointRevalidationFilter.class);
        logs = new ListAppender<>();
        logs.start();
        filterLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    private AuthorizationEndpointRevalidationFilter novoFiltro(Duration maxLifetime, boolean enabled) {
        return new AuthorizationEndpointRevalidationFilter(
                ENDPOINT, userDetailsService, revocationGuard, maxLifetime, enabled);
    }

    // ── AC-33: matcher POSITIVO — não-atuação nas demais rotas ───────────────────

    @Test
    @DisplayName("AC-33: não atua em /connect/logout, /oauth2/token, /login nem /default-ui.css")
    void deveNaoAtuar_foraDoAuthorizationEndpoint() throws Exception {
        // /connect/logout é o que carrega peso: ele está DENTRO do endpointsMatcher da chain
        // @Order(1) (via oidc(withDefaults())), então é onde um matcher frouxo faria estrago —
        // invalidar a sessão no front-channel do RP-Initiated Logout (ADR-018).
        for (String path : List.of("/connect/logout", "/oauth2/token", "/login", "/default-ui.css",
                "/oauth2/jwks", "/.well-known/openid-configuration")) {
            autenticarNaSessao(sessaoCom(Instant.now()), List.of("ROLE_USER", "USER_ID:" + USER_ID));
            MockFilterChain chain = executar(requisicao("GET", path, new MockHttpSession()));

            assertNotNull(chain.getRequest(), "a chain deve seguir em " + path);
            verifyNoInteractions(userDetailsService);
            assertTrue(logs.list.isEmpty(), "não deve logar decisão alguma em " + path);
        }
    }

    @Test
    @DisplayName("AC-33: atua no authorization endpoint tanto em GET quanto em POST")
    void deveAtuar_emGetEPostNoAuthorizationEndpoint() throws Exception {
        // doThrow (e não when(...).thenThrow): re-stubar um mock já configurado para lançar faria a
        // própria chamada de stubbing lançar na segunda volta do laço.
        org.mockito.Mockito.doThrow(new UsernameNotFoundException("removido"))
                .when(userDetailsService).loadUserByUsername(EMAIL);

        for (String metodo : List.of("GET", "POST")) {
            MockHttpSession session = sessaoCom(Instant.now());
            autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));

            executar(requisicao(metodo, ENDPOINT, session));

            assertEquals(AuthorizationEndpointRevalidationFilter.Reason.NOT_FOUND.name(),
                    motivoLogado(), "o filtro deve atuar em " + metodo);
            logs.list.clear();
        }
    }

    @Test
    @DisplayName("Kill switch: desabilitado, o filtro é no-op mesmo no authorization endpoint")
    void deveSerNoOp_quandoDesabilitado() throws Exception {
        filter = novoFiltro(Duration.ofHours(8), false);
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest());
        verifyNoInteractions(userDetailsService);
        assertFalse(session.isInvalid());
    }

    // ── Caminho feliz e AC-06 ───────────────────────────────────────────────────

    @Test
    @DisplayName("AC-01: sessão íntegra segue a chain, sem invalidar")
    void deveSeguir_quandoEstadoCoincide() throws Exception {
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:" + USER_ID));

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest(), "a chain deve seguir");
        assertFalse(session.isInvalid(), "a sessão íntegra não pode ser invalidada");
        assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
                "o SecurityContext deve permanecer intacto");
        assertTrue(logs.list.isEmpty(), "caminho feliz não emite linha de invalidação");
    }

    @Test
    @DisplayName("AC-06: divergência INVALIDA, não atualiza as authorities in-place")
    void deveInvalidar_semSubstituirAuthorities() throws Exception {
        MockHttpSession session = sessaoCom(Instant.now());
        Authentication original = autenticarNaSessao(session,
                List.of("ROLE_ADMIN", "ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:" + USER_ID));

        executar(requisicao("GET", ENDPOINT, session));

        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "o SecurityContext tem de ser limpo, não reescrito com as authorities novas");
        assertTrue(original.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())),
                "as authorities da sessão não são mutadas in-place");
        assertEquals("AUTHORITIES_DIVERGED", motivoLogado());
    }

    @Test
    @DisplayName("Restrição 1: divergência SÓ no USER_ID: (mesmo e-mail, mesmas roles) também invalida")
    void deveInvalidar_quandoApenasOUserIdDiverge() throws Exception {
        // Delete + re-registro com o mesmo e-mail e as mesmas roles. Comparar só ROLE_* deixaria
        // passar, e o TokenCustomizerConfig cunharia um token cujo claim userID aponta para um
        // documento que não existe mais — o bug original por outra porta.
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:id-antigo"));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:id-novo"));

        executar(requisicao("GET", ENDPOINT, session));

        assertTrue(session.isInvalid(), "userID divergente tem de invalidar a sessão");
        assertEquals("AUTHORITIES_DIVERGED", motivoLogado());
    }

    @Test
    @DisplayName("Authority de FATOR do framework (FACTOR_PASSWORD) não conta como divergência")
    void deveIgnorarAuthoritiesDeFatorDoFramework() throws Exception {
        // REGRESSÃO REAL, pega pelo teste de integração e não por este: o DaoAuthenticationProvider do
        // Spring Security 7 acrescenta FACTOR_PASSWORD ao token do login, e o UserDetails re-derivado
        // não a tem. Comparar os conjuntos CRUS fazia toda sessão divergir de si mesma — o filtro
        // invalidava TODO login, que é a pior regressão possível desta correção (a que a asserção (f)
        // do smoke-test existe para distinguir do fix correto).
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session,
                List.of("ROLE_USER", "USER_ID:" + USER_ID, "FACTOR_PASSWORD"));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:" + USER_ID));

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest());
        assertFalse(session.isInvalid(), "authority de fator do framework não é divergência de domínio");
    }

    // ── Grupo A: estados da fonte de verdade ────────────────────────────────────

    @Test
    @DisplayName("AC-02/AC-03: titular eliminado ou inativo (404 do canal interno) invalida com NOT_FOUND")
    void deveInvalidar_quandoTitularInexistenteOuInativo() throws Exception {
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException("inexistente ou inativo"));

        executar(requisicao("GET", ENDPOINT, session));

        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("NOT_FOUND", motivoLogado());
    }

    @Test
    @DisplayName("AC-04: enabled=false (e-mail não verificado fora da carência) invalida com DISABLED")
    void deveInvalidar_quandoEmailNaoVerificadoForaDaCarencia() throws Exception {
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(false, "ROLE_USER", "USER_ID:" + USER_ID));

        executar(requisicao("GET", ENDPOINT, session));

        assertTrue(session.isInvalid());
        assertEquals("DISABLED", motivoLogado());
    }

    // ── AC-09: lockout NÃO participa da comparação ──────────────────────────────

    @Test
    @DisplayName("AC-09 [NEGATIVO]: conta bloqueada pelo lockout NÃO invalida a sessão viva")
    void deveNaoInvalidar_quandoContaBloqueadaPeloLockout() throws Exception {
        // Se accountNonLocked entrasse na comparação, errar 5 senhas derrubaria a sessão de um
        // terceiro que só conhece o e-mail: o lockout do ADR-010 viraria DoS contra sessão viva.
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        UserDetails bloqueado = new User(EMAIL, "hash", true, true, true, /* accountNonLocked */ false,
                AuthorityUtils.createAuthorityList("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(bloqueado);

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest());
        assertFalse(session.isInvalid(), "lockout não pode invalidar sessão viva");
        assertTrue(logs.list.isEmpty());
    }

    // ── AC-10 / AC-11: degradação e fail-open ───────────────────────────────────

    @Test
    @DisplayName("AC-10 [NEGATIVO]: user-service fora + epoch POSTERIOR à autenticação → invalida")
    void deveDegradarParaOEpoch_eInvalidar_quandoRevogadoDepoisDaAutenticacao() throws Exception {
        // Truncado a millis: o atributo de sessão é Long de epoch millis (AC-35), então o Instant que
        // o filtro reconstrói não carrega nanos — comparar com um Instant.now() cru nunca casaria.
        Instant autenticadoEm = Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        MockHttpSession session = sessaoCom(autenticadoEm);
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UserServiceUnavailableException("circuito aberto"));
        when(revocationGuard.isRevoked(USER_ID, autenticadoEm)).thenReturn(true);

        executar(requisicao("GET", ENDPOINT, session));

        assertTrue(session.isInvalid());
        assertEquals("REVOKED_EPOCH", motivoLogado());
        // O instante comparado é o da AUTENTICAÇÃO, não "agora": com um T0 que avançasse, o epoch
        // nunca seria maior e o caminho degradado viraria decorativo.
        verify(revocationGuard).isRevoked(USER_ID, autenticadoEm);
    }

    @Test
    @DisplayName("AC-10 [NEGATIVO]: user-service fora sem epoch → emite normalmente, com WARN")
    void deveEmitir_quandoUserServiceForaESemEpoch() throws Exception {
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UserServiceUnavailableException("timeout"));
        when(revocationGuard.isRevoked(anyString(), any())).thenReturn(false);

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest(), "indisponibilidade não pode derrubar o login");
        assertFalse(session.isInvalid());
        assertTrue(logs.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("fail-open")),
                "o caminho de fail-open tem de ser distinguível no log (AC-37)");
    }

    @Test
    @DisplayName("AC-11 [NEGATIVO]: user-service fora E Redis fora → fail-open, sessão preservada")
    void deveFalharAberto_quandoUserServiceERedisFora() throws Exception {
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UserServiceUnavailableException("conexão recusada"));
        // O guard é fail-open por dentro (ADR-017): erro de Redis vira false, não exceção.
        when(revocationGuard.isRevoked(anyString(), any())).thenReturn(false);

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest());
        assertFalse(session.isInvalid());
    }

    // ── AC-16 / AC-17: teto de vida ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-16: instante de autenticação além do teto invalida antes de qualquer I/O")
    void deveInvalidar_quandoAlemDoTetoDeVida() throws Exception {
        // Duration configurável — NUNCA Thread.sleep (antipadrão declarado no projeto).
        filter = novoFiltro(Duration.ofMinutes(30), true);
        MockHttpSession session = sessaoCom(Instant.now().minusSeconds(3600));
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));

        executar(requisicao("GET", ENDPOINT, session));

        assertTrue(session.isInvalid());
        assertEquals("MAX_LIFETIME", motivoLogado());
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("AC-17 [NEGATIVO]: dentro do teto, a sessão não é invalidada por ele")
    void deveNaoInvalidar_quandoDentroDoTeto() throws Exception {
        filter = novoFiltro(Duration.ofHours(8), true);
        MockHttpSession session = sessaoCom(Instant.now().minusSeconds(3600));
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:" + USER_ID));

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest());
        assertFalse(session.isInvalid());
    }

    @Test
    @DisplayName("AC-15: sessão sem o carimbo cai em getCreationTime(), sem lançar")
    void deveUsarCreationTimeComoFallback_quandoSemCarimbo() throws Exception {
        MockHttpSession session = new MockHttpSession(); // criada "agora", sem AUTH_INSTANT
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:" + USER_ID));

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, session));

        assertNotNull(chain.getRequest(), "ausência do atributo não pode lançar nem invalidar");
        assertFalse(session.isInvalid());
        assertFalse(AuthenticationInstantAttribute.isPresent(session));
    }

    // ── AC-38: a re-derivação não re-carimba o instante ─────────────────────────

    @Test
    @DisplayName("AC-38 [NEGATIVO]: N autorizações não movem o instante de autenticação")
    void deveNaoRecarimbarInstanteDeAutenticacao() throws Exception {
        Instant t0 = Instant.now().minusSeconds(120);
        MockHttpSession session = sessaoCom(t0);
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:" + USER_ID));

        for (int i = 0; i < 5; i++) {
            autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
            executar(requisicao("GET", ENDPOINT, session));
        }

        assertEquals(t0.toEpochMilli(),
                ((Long) session.getAttribute(AuthenticationInstantAttribute.NAME)).longValue(),
                "o carimbo tem de permanecer em T0 — avançá-lo esvaziaria o teto E a degradação por epoch");
    }

    // ── Sessão/autenticação ausentes ────────────────────────────────────────────

    @Test
    @DisplayName("Authorize anônimo (sem autenticação) passa direto — é o primeiro acesso normal")
    void deveSeguir_quandoAnonimo() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        MockFilterChain chain = executar(requisicao("GET", ENDPOINT, new MockHttpSession()));

        assertNotNull(chain.getRequest());
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("Autenticado sem sessão HTTP passa direto (nada a invalidar)")
    void deveSeguir_quandoSemSessao() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(EMAIL, "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER", "USER_ID:" + USER_ID)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ENDPOINT);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        verifyNoInteractions(userDetailsService);
    }

    // ── AC-37: motivo discriminado, e-mail mascarado ────────────────────────────

    @Test
    @DisplayName("AC-37: a linha de invalidação traz motivo discriminado e e-mail mascarado")
    void deveLogarMotivoDiscriminadoComEmailMascarado() throws Exception {
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException("eliminado"));

        executar(requisicao("GET", ENDPOINT, session));

        String linha = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("motivo:"))
                .findFirst()
                .orElseThrow();
        assertTrue(linha.contains("| revalidação |"), "formato em pipe de docs/LOGS.md: " + linha);
        assertTrue(linha.contains("motivo: NOT_FOUND"), linha);
        assertFalse(linha.contains(EMAIL), "PII não pode sair em claro: " + linha);
        assertTrue(linha.contains("t***@test.com"), "e-mail deve ir por LogUtils.maskEmail: " + linha);
        assertTrue(linha.contains(USER_ID), linha);
    }

    // ── AC-32: nenhum evento de autenticação é publicado ────────────────────────

    @Test
    @DisplayName("AC-32 [NEGATIVO]: a re-derivação usa UserDetailsService direto, nunca o AuthenticationManager")
    void deveChamarUserDetailsServiceDireto() throws Exception {
        // Guard estrutural do B-4: passar por AuthenticationManager/ProviderManager publicaria
        // AuthenticationSuccessEvent e o LoginAttemptListener ZERARIA o lockout sem prova de senha.
        // Aqui não há AuthenticationManager algum entre as dependências do filtro — o teste de
        // integração (LoginLockoutIntegrationTest) confere o contador de fato.
        MockHttpSession session = sessaoCom(Instant.now());
        autenticarNaSessao(session, List.of("ROLE_USER", "USER_ID:" + USER_ID));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, "ROLE_USER", "USER_ID:" + USER_ID));

        executar(requisicao("GET", ENDPOINT, session));

        verify(userDetailsService).loadUserByUsername(EMAIL);
        verify(revocationGuard, never()).isRevoked(anyString(), any());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private MockHttpSession sessaoCom(Instant autenticadoEm) {
        MockHttpSession session = new MockHttpSession();
        AuthenticationInstantAttribute.write(session, autenticadoEm);
        return session;
    }

    private Authentication autenticarNaSessao(HttpSession session, List<String> authorities) {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(EMAIL, "n/a",
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private UserDetails usuario(boolean enabled, String... authorities) {
        return new User(EMAIL, "hash", enabled, true, true, true,
                AuthorityUtils.createAuthorityList(authorities));
    }

    private MockHttpServletRequest requisicao(String metodo, String path, HttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, path);
        request.setSession(session);
        return request;
    }

    private MockFilterChain executar(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    private String motivoLogado() {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("motivo:"))
                .findFirst()
                .map(m -> m.replaceAll("(?s).*motivo: (\\w+).*", "$1"))
                .orElse(null);
    }
}
