package com.users.userservice.services;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.domain.NotificationType;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.EmailVerificationRequestDTO;
import com.users.userservice.exceptions.InvalidVerificationTokenException;
import com.users.userservice.repository.INotificationOutboxRepository;
import com.users.userservice.repository.IUserRepository;
import com.users.userservice.util.LogUtils;

/**
 * Confirmação e reenvio do e-mail de verificação de cadastro (ADR-015). Também concentra a
 * criação do outbox + disparo assíncrono ({@link #issueVerificationEmail(User)}), reaproveitado
 * tanto pelo {@code RegisterService} (cadastro) quanto pelo reenvio — evita duplicar a lógica
 * de outbox em dois lugares.
 */
@Service
public class EmailVerificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationService.class);

    private final IUserRepository userRepository;
    private final INotificationOutboxRepository outboxRepository;
    private final VerificationTokenService tokenService;
    private final NotificationDispatchService dispatchService;
    private final ResendRateLimitService resendRateLimitService;
    private final CacheService cacheService;
    private final AuditService auditService;
    private final String verificationBaseUrl;
    private final Duration outboxRetention;

    public EmailVerificationService(
            IUserRepository userRepository,
            INotificationOutboxRepository outboxRepository,
            VerificationTokenService tokenService,
            NotificationDispatchService dispatchService,
            ResendRateLimitService resendRateLimitService,
            CacheService cacheService,
            AuditService auditService,
            @Value("${app.verification.base-url:http://localhost:8081}") String verificationBaseUrl,
            @Value("${app.verification.outbox-retention:30d}") Duration outboxRetention) {
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
        this.tokenService = tokenService;
        this.dispatchService = dispatchService;
        this.resendRateLimitService = resendRateLimitService;
        this.cacheService = cacheService;
        this.auditService = auditService;
        this.verificationBaseUrl = verificationBaseUrl;
        this.outboxRetention = outboxRetention;
    }

    /**
     * Gera o token, persiste o outbox (PENDING) e dispara o envio de forma assíncrona — nunca
     * lança para o chamador (cadastro/reenvio têm que ter sucesso independente do e-mail).
     */
    public void issueVerificationEmail(User user) {
        String token = tokenService.generateToken();
        Instant now = Instant.now();

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setUserId(user.getId());
        outbox.setType(NotificationType.EMAIL_VERIFICATION);
        outbox.setTokenHash(tokenService.hashToken(token));
        outbox.setExpiresAt(now.plus(tokenService.getTokenTtl()));
        outbox.setStatus(NotificationStatus.PENDING);
        outbox.setCreatedAt(now);
        outbox.setAttempts(0);
        // Retenção de auditoria do outbox além da expiração do token (ver ADR-015, fechamento
        // do C1) — o TTL index do Mongo atua sobre purgeAt, não sobre expiresAt.
        outbox.setPurgeAt(now.plus(outboxRetention));
        outbox = outboxRepository.save(outbox);

        String verificationLink = verificationBaseUrl + "/v1/users/verify-email?token=" + token;
        EmailVerificationRequestDTO request =
                new EmailVerificationRequestDTO(user.getEmail(), user.getName(), verificationLink);

        dispatchService.dispatchEmailVerification(outbox.getId(), request);
    }

    /**
     * Confirma o e-mail a partir do token recebido na URL. Resposta sempre genérica em caso de
     * falha (token inexistente/expirado/já usado) — não revela o motivo específico, evitando
     * oráculo de enumeração.
     */
    public void confirm(String token) {
        String tokenHash = tokenService.hashToken(token);
        NotificationOutbox outbox = outboxRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidVerificationTokenException::new);

        // Idempotente: clique duplicado no mesmo link já confirmado é sucesso silencioso.
        if (outbox.getStatus() == NotificationStatus.CONFIRMED) {
            return;
        }
        boolean confirmable = (outbox.getStatus() == NotificationStatus.PENDING
                || outbox.getStatus() == NotificationStatus.SENT)
                && outbox.getExpiresAt() != null
                && outbox.getExpiresAt().isAfter(Instant.now());
        if (!confirmable) {
            throw new InvalidVerificationTokenException();
        }

        User user = userRepository.findById(outbox.getUserId())
                .orElseThrow(InvalidVerificationTokenException::new);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        cacheService.evictById(user.getId());
        cacheService.evictByEmail(user.getEmail());
        cacheService.evictByEmailAuth(user.getEmail());

        outbox.setStatus(NotificationStatus.CONFIRMED);
        outbox.setLastAttemptAt(Instant.now());
        outboxRepository.save(outbox);

        auditService.recordEmailVerified(user.getId(), user.getEmail());
        LOGGER.info("| EMAIL-VERIFICATION | confirmado | userId: {}", user.getId());
    }

    /**
     * Reenvia o e-mail de verificação. Resposta ao chamador (controller) é sempre a mesma,
     * independente do branch interno (anti-enumeração, ADR-015/AC-10) — este método nunca
     * lança nem retorna sinal observável de qual caminho seguiu.
     */
    public void resend(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return;
        }
        User user = userOpt.get();
        // emailVerified==null é legado (tratado como verificado) — só reenvia para false explícito.
        if (!Boolean.FALSE.equals(user.getEmailVerified())) {
            return;
        }
        if (resendRateLimitService.isThrottled(email)) {
            LOGGER.warn("| EMAIL-VERIFICATION | reenvio throttled | email: {}", LogUtils.maskEmail(email));
            return;
        }
        resendRateLimitService.recordResend(email);

        // Supera o(s) outbox(es) pendente(s) anterior(es): só o token mais recente vale.
        List<NotificationOutbox> previous = outboxRepository.findByUserIdAndTypeAndStatusIn(
                user.getId(),
                NotificationType.EMAIL_VERIFICATION,
                List.of(NotificationStatus.PENDING, NotificationStatus.SENT));
        previous.forEach(o -> o.setStatus(NotificationStatus.SUPERSEDED));
        outboxRepository.saveAll(previous);

        issueVerificationEmail(user);
    }
}
