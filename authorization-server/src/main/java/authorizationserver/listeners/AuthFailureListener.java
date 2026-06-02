package authorizationserver.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.stereotype.Component;

import authorizationserver.util.LogUtils;

/**
 * Loga falhas de autenticação (ex.: senha incorreta) publicadas pelo Spring
 * Security via {@code DefaultAuthenticationEventPublisher}. Sem isto não há
 * visibilidade de tentativas de login malsucedidas / brute-force.
 */
@Component
public class AuthFailureListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthFailureListener.class);

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        String email = event.getAuthentication().getName();
        LOGGER.warn(
            "| auth | login falhou | email: {} | motivo: {}",
            LogUtils.maskEmail(email),
            event.getException().getClass().getSimpleName()
        );
    }
}
