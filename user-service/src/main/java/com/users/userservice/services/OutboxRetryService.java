package com.users.userservice.services;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.domain.NotificationType;
import com.users.userservice.domain.User;
import com.users.userservice.repository.INotificationOutboxRepository;
import com.users.userservice.repository.IUserRepository;

/**
 * Varredura periódica do outbox de verificação de e-mail — reprocessa o que o disparo por evento
 * deixou para trás (emenda do ADR-015, que originalmente aceitou "outbox sem retry automático").
 *
 * <p>Sem esta varredura um outbox {@code FAILED} é um beco sem saída: o
 * {@code NotificationDispatchService} marca a falha e nada mais toca o registro. O titular fica
 * com {@code emailVerified=false} e, passada a carência de 24h do ADR-015, a conta se torna
 * inacessível — a única saída seria o reenvio manual, que exige o titular saber que precisa pedir.
 *
 * <p><b>Por que o retry emite um token NOVO em vez de reenviar o mesmo e-mail.</b> O outbox
 * persiste apenas o {@code tokenHash}; o token em claro nunca é salvo (decisão de segurança do
 * ADR-015). Não há como remontar o link original, então o retry supera o registro antigo
 * ({@code SUPERSEDED}) e emite um par token/link novo, pelo mesmo caminho do reenvio manual.
 */
@Service
public class OutboxRetryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRetryService.class);

    private static final String LOCK_KEY = "outbox_retry:lock";

    private static final List<NotificationStatus> RETRYABLE =
            List.of(NotificationStatus.FAILED, NotificationStatus.PENDING);

    private final INotificationOutboxRepository outboxRepository;
    private final IUserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final StringRedisTemplate redis;
    private final Duration backoff;
    private final Duration lockTtl;
    private final int maxAttempts;

    public OutboxRetryService(
            INotificationOutboxRepository outboxRepository,
            IUserRepository userRepository,
            EmailVerificationService emailVerificationService,
            StringRedisTemplate redis,
            @Value("${app.verification.retry-backoff:15m}") Duration backoff,
            @Value("${app.verification.retry-interval:5m}") Duration interval,
            @Value("${app.verification.retry-max-attempts:5}") int maxAttempts) {
        this.outboxRepository = outboxRepository;
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.redis = redis;
        this.backoff = backoff;
        // O lock tem de sobreviver ao ciclo inteiro, senão dois nós se sobrepõem no fim da
        // varredura; folga de 2x sobre o intervalo.
        this.lockTtl = interval.multipliedBy(2);
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(
        fixedDelayString = "${app.verification.retry-interval:5m}",
        initialDelayString = "${app.verification.retry-interval:5m}")
    public void retryPendingNotifications() {
        if (!acquireLock()) {
            return;
        }
        List<NotificationOutbox> candidates =
                outboxRepository.findTop100ByTypeAndStatusInOrderByCreatedAtAsc(
                        NotificationType.EMAIL_VERIFICATION, RETRYABLE);

        int retried = 0;
        for (NotificationOutbox outbox : candidates) {
            if (retry(outbox)) {
                retried++;
            }
        }
        if (retried > 0) {
            LOGGER.info(
                "| OUTBOX-RETRY | varredura concluída | candidatos: {} | reemitidos: {}",
                candidates.size(), retried);
        }
    }

    /**
     * Reserva a varredura para uma única instância. <b>Fail-closed de propósito, ao contrário do
     * resto do sistema</b>: cache, rate limit e revogação de token são fail-open porque um Redis
     * indisponível não pode barrar autenticação. Aqui o inverso — sem lock confirmado a varredura
     * não roda. Falhar aberto com N instâncias significaria N e-mails duplicados por ciclo, e o
     * custo de pular um ciclo é nulo (o próximo apanha os mesmos registros).
     */
    private boolean acquireLock() {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(LOCK_KEY, Instant.now().toString(), lockTtl));
        } catch (RuntimeException e) {
            LOGGER.warn("| OUTBOX-RETRY | varredura pulada, lock indisponível | cause: {}", e.getMessage());
            return false;
        }
    }

    /** @return true se o registro foi reemitido; false se foi pulado por qualquer critério. */
    private boolean retry(NotificationOutbox outbox) {
        if (!backoffElapsed(outbox)) {
            return false;
        }
        Optional<User> userOpt = userRepository.findById(outbox.getUserId());
        if (userOpt.isEmpty()) {
            // Titular apagado (hard-delete) com outbox ainda em retenção: encerra o registro.
            markSuperseded(outbox);
            return false;
        }
        User user = userOpt.get();
        // emailVerified==null é legado (tratado como verificado); só reemite para false explícito.
        // Cobre também quem confirmou por outro link no meio tempo.
        if (!Boolean.FALSE.equals(user.getEmailVerified())) {
            markSuperseded(outbox);
            return false;
        }
        if (outboxRepository.countByUserIdAndType(
                user.getId(), NotificationType.EMAIL_VERIFICATION) >= maxAttempts) {
            markSuperseded(outbox);
            LOGGER.warn(
                "| OUTBOX-RETRY | teto de tentativas atingido, desistindo | userId: {} | teto: {}",
                user.getId(), maxAttempts);
            return false;
        }

        // Lido ANTES do markSuperseded, que sobrescreve o status — senão o log diria sempre
        // "SUPERSEDED" e perderia justamente a informação útil (veio de FAILED ou de PENDING?).
        NotificationStatus previousStatus = outbox.getStatus();
        markSuperseded(outbox);
        emailVerificationService.issueVerificationEmail(user);
        LOGGER.info(
            "| OUTBOX-RETRY | token reemitido | userId: {} | outboxAnterior: {} | statusAnterior: {}",
            user.getId(), outbox.getId(), previousStatus);
        return true;
    }

    /**
     * Só reprocessa depois da janela de backoff. Vale tanto para o {@code FAILED} (espaça a
     * retentativa enquanto o notification-service não volta) quanto para o {@code PENDING}, que
     * nesta janela ainda pode estar em voo no executor assíncrono — reemitir antes disso
     * duplicaria um e-mail que estava para dar certo.
     */
    private boolean backoffElapsed(NotificationOutbox outbox) {
        Instant reference = outbox.getLastAttemptAt() != null
                ? outbox.getLastAttemptAt()
                : outbox.getCreatedAt();
        return reference == null || reference.isBefore(Instant.now().minus(backoff));
    }

    private void markSuperseded(NotificationOutbox outbox) {
        outbox.setStatus(NotificationStatus.SUPERSEDED);
        outbox.setLastAttemptAt(Instant.now());
        outbox.setAttempts(outbox.getAttempts() + 1);
        outboxRepository.save(outbox);
    }
}
