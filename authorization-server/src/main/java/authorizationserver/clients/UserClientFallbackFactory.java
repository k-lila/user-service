package authorizationserver.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

@Component
public class UserClientFallbackFactory implements FallbackFactory<IUserClient> {

    private static final Logger log = LoggerFactory.getLogger(UserClientFallbackFactory.class);

    @Override
    public IUserClient create(Throwable cause) {
        return email -> {
            log.warn("| [CIRCUIT-BREAKER] | user-service indisponível | email: {} | cause: {}",
                    maskEmail(email), cause.getMessage());
            throw new UsernameNotFoundException("user-service unavailable");
        };
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }
}
