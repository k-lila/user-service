package com.users.userservice.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.users.userservice.dtos.EmailVerificationRequestDTO;
import com.users.userservice.util.LogUtils;

@Component
public class NotificationClientFallbackFactory implements FallbackFactory<INotificationClient> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationClientFallbackFactory.class);

    @Override
    public INotificationClient create(Throwable cause) {
        return request -> {
            LOGGER.warn(
                "| [CIRCUIT-BREAKER] | notification-service indisponível | email: {} | cause: {}",
                LogUtils.maskEmail(request.getEmail()),
                cause.getMessage()
            );
            throw new NotificationServiceUnavailableException(cause);
        };
    }

    // Exceção dedicada do fallback — RegisterService/EmailVerificationService a capturam
    // junto com qualquer outra falha para marcar o outbox como FAILED, sem nunca propagar
    // ao chamador do cadastro/reenvio.
    public static class NotificationServiceUnavailableException extends RuntimeException {
        public NotificationServiceUnavailableException(Throwable cause) {
            super("notification-service indisponível", cause);
        }
    }
}
