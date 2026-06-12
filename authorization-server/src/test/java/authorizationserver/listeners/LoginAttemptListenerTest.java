package authorizationserver.listeners;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import authorizationserver.services.LoginAttemptService;

@ExtendWith(MockitoExtension.class)
class LoginAttemptListenerTest {

    @Mock private LoginAttemptService attempts;
    @InjectMocks private LoginAttemptListener listener;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequestWithIp(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void deveContarFalha_quandoFormLogin() {
        bindRequestWithIp("203.0.113.7");
        var auth = new UsernamePasswordAuthenticationToken("fulano@email.com", "senha");
        var event = new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad"));

        listener.onFailure(event);

        verify(attempts).recordFailure("fulano@email.com", "203.0.113.7");
    }

    @Test
    void naoDeveContarFalha_quandoNaoEhFormLogin() {
        var auth = new TestingAuthenticationToken("client-id", "secret");
        var event = new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad"));

        listener.onFailure(event);

        verifyNoInteractions(attempts);
    }

    @Test
    void deveResetarContador_quandoSucessoFormLogin() {
        bindRequestWithIp("203.0.113.7");
        var auth = new UsernamePasswordAuthenticationToken("fulano@email.com", "senha");
        var event = new AuthenticationSuccessEvent(auth);

        listener.onSuccess(event);

        verify(attempts).loginSucceeded("fulano@email.com", "203.0.113.7");
    }

    @Test
    void naoDeveResetar_quandoSucessoNaoEhFormLogin() {
        var auth = new TestingAuthenticationToken("client-id", "secret");
        var event = new AuthenticationSuccessEvent(auth);

        listener.onSuccess(event);

        verifyNoInteractions(attempts);
    }

    @Test
    void deveUsarIpUnknown_quandoForaDeRequest() {
        var auth = new UsernamePasswordAuthenticationToken("fulano@email.com", "senha");
        var event = new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad"));

        listener.onFailure(event);

        verify(attempts).recordFailure("fulano@email.com", "unknown");
    }
}
