package authorizationserver.listeners;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import authorizationserver.services.LoginAttemptService;
import authorizationserver.util.ClientIpResolver;

/**
 * Alimenta o {@link LoginAttemptService} a partir dos eventos de autenticação.
 *
 * <p>Conta apenas {@link AuthenticationFailureBadCredentialsEvent} (senha errada) —
 * NÃO conta {@code AuthenticationFailureLockedEvent} (senão o lock se auto-renova e
 * nunca expira) nem falhas de comunicação interna. O guard {@code instanceof
 * UsernamePasswordAuthenticationToken} restringe ao form login do usuário, ignorando
 * a autenticação de client OAuth2 no token endpoint.
 *
 * <p>Separado do {@code AuthFailureListener} (que permanece só para log).
 */
@Component
public class LoginAttemptListener {

    private final LoginAttemptService attempts;

    public LoginAttemptListener(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken auth) {
            attempts.recordFailure(auth.getName(), ClientIpResolver.currentIp());
        }
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken auth) {
            attempts.loginSucceeded(auth.getName(), ClientIpResolver.currentIp());
        }
    }
}
