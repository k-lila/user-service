package com.users.userservice.services;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.users.userservice.clients.INotificationClient;
import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.dtos.EmailVerificationRequestDTO;
import com.users.userservice.repository.INotificationOutboxRepository;
import com.users.userservice.util.LogUtils;

/**
 * Disparo assíncrono da chamada Feign ao notification-service (ADR-015) — bean externo
 * dedicado porque {@code @Async} só intercepta chamadas que passam pelo proxy do Spring;
 * chamar este método de dentro da própria classe que cria o outbox não funcionaria.
 *
 * <p>Isolamento de falha obrigatório: qualquer exceção (circuit breaker aberto, timeout,
 * 4xx/5xx do notification-service) é capturada aqui e só rebate no status do outbox
 * ({@code FAILED}) — nunca propaga para o cadastro/reenvio que disparou o envio.
 */
@Service
public class NotificationDispatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final INotificationClient notificationClient;
    private final INotificationOutboxRepository outboxRepository;

    public NotificationDispatchService(
            INotificationClient notificationClient,
            INotificationOutboxRepository outboxRepository) {
        this.notificationClient = notificationClient;
        this.outboxRepository = outboxRepository;
    }

    @Async("notificationExecutor")
    public void dispatchEmailVerification(String outboxId, EmailVerificationRequestDTO request) {
        try {
            notificationClient.sendEmailVerification(request);
            markStatus(outboxId, NotificationStatus.SENT);
            LOGGER.info("| OUTBOX | envio confirmado | outboxId: {}", outboxId);
        } catch (RuntimeException e) {
            // Inclui a NotificationServiceUnavailableException do fallback do circuit breaker.
            markStatus(outboxId, NotificationStatus.FAILED);
            LOGGER.warn(
                "| OUTBOX | falha no envio | outboxId: {} | email: {} | cause: {}",
                outboxId,
                LogUtils.maskEmail(request.getEmail()),
                e.getMessage()
            );
        }
    }

    private void markStatus(String outboxId, NotificationStatus status) {
        outboxRepository.findById(outboxId).ifPresent(outbox -> {
            outbox.setStatus(status);
            outbox.setLastAttemptAt(Instant.now());
            outbox.setAttempts(outbox.getAttempts() + 1);
            outboxRepository.save(outbox);
        });
    }
}
