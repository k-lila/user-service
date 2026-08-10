package authorizationserver.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import authorizationserver.session.AuthenticationInstantAttribute;

/** AC-14 e AC-35 (tipo do atributo) no nível unitário. */
class AuthenticationInstantListenerTest {

    private final AuthenticationInstantListener listener = new AuthenticationInstantListener();

    private MockHttpServletRequest request;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("AC-14: login por form carimba o instante de autenticação na sessão")
    void deveCarimbar_quandoLoginPorUsernamePassword() {
        Instant antes = Instant.now().minusMillis(1);

        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated("titular@test.com", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER"))));

        assertTrue(AuthenticationInstantAttribute.isPresent(session));
        Instant carimbo = AuthenticationInstantAttribute.read(session);
        assertFalse(carimbo.isBefore(antes), "o carimbo deve ser o instante do login");
    }

    @Test
    @DisplayName("AC-35: o valor gravado é Long de epoch millis (serializável pelo Redis/JDK)")
    void deveGravarLongDeEpochMillis() {
        // Precedente do ADR-021: três testes de integração quebraram por SerializationException ao
        // guardar objeto não-serializável na sessão. Aqui a falha seria pior — o carimbo acontece em
        // TODO login bem-sucedido, então quebraria o login inteiro, não só o caminho de falha.
        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated("titular@test.com", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER"))));

        Object valor = session.getAttribute(AuthenticationInstantAttribute.NAME);
        assertInstanceOf(Long.class, valor, "nem Instant, nem tipo customizado: Long de epoch millis");
        assertInstanceOf(java.io.Serializable.class, valor);
    }

    @Test
    @DisplayName("AC-14 [NEGATIVO]: Authentication de outro tipo não dispara o carimbo")
    void deveIgnorar_quandoOutroTipoDeAuthentication() {
        // Mesmo guard do LoginAttemptListener: a autenticação de client OAuth2 no token endpoint não
        // é login de titular e não tem sessão a carimbar.
        listener.onSuccess(new AuthenticationSuccessEvent(
                new TestingAuthenticationToken("gateway-client", "secret", "ROLE_CLIENT")));

        assertNull(session.getAttribute(AuthenticationInstantAttribute.NAME));
    }

    @Test
    @DisplayName("Fora de um request web, não lança e não grava")
    void deveIgnorar_quandoSemRequestWeb() {
        RequestContextHolder.resetRequestAttributes();

        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated("titular@test.com", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER"))));

        assertNull(session.getAttribute(AuthenticationInstantAttribute.NAME));
    }

    @Test
    @DisplayName("AC-15: read() cai em getCreationTime() quando o atributo não existe")
    void deveUsarCreationTimeComoFallback() {
        MockHttpSession semCarimbo = new MockHttpSession();

        assertFalse(AuthenticationInstantAttribute.isPresent(semCarimbo));
        assertEquals(semCarimbo.getCreationTime(),
                AuthenticationInstantAttribute.read(semCarimbo).toEpochMilli());
    }
}
