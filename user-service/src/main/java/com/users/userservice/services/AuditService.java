package com.users.userservice.services;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.AuditAction;
import com.users.userservice.domain.AuditLog;
import com.users.userservice.repository.IAuditLogRepository;
import com.users.userservice.util.LogUtils;

/**
 * Grava a trilha de auditoria de acesso a dado pessoal (LGPD, item 1.4 RELATORIOA) na coleção
 * {@code auditLogs}. Distinta do log operacional SLF4J: é registro de negócio durável.
 *
 * <p>Escrita <b>assíncrona</b> (executor {@code auditExecutor}) e <b>isolada de falha</b>: um erro
 * ao persistir a auditoria é logado em ERROR mas nunca propaga para a operação de negócio. Os
 * métodos públicos são o ponto de corte {@code @Async} — chamados pelos controllers (beans
 * externos), o proxy se aplica; a montagem do registro e a leitura do MDC (traceId) ocorrem na
 * thread do executor, que herda o MDC via o {@code TaskDecorator} de {@code AuditAsyncConfig}.
 */
@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);
    private static final String SYSTEM = "SYSTEM";
    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";

    private final IAuditLogRepository auditLogRepository;
    private final Duration retention;

    public AuditService(
            IAuditLogRepository auditLogRepository,
            @Value("${app.audit.retention:180d}") Duration retention) {
        this.auditLogRepository = auditLogRepository;
        this.retention = retention;
    }

    /** Auto-registro: não há JWT (endpoint público); o ator é o próprio usuário criado. */
    @Async("auditExecutor")
    public void recordRegistration(String newUserId, String email) {
        AuditLog log = base(AuditAction.REGISTER, newUserId, email);
        log.setActorType(USER);
        log.setActorUserId(newUserId);
        log.setActorRoles(Set.of(USER));
        persist(log);
    }

    /**
     * Confirmação de verificação de e-mail (ADR-015): não há JWT (endpoint público,
     * pré-sessão); o ator é o próprio titular cujo e-mail foi confirmado.
     */
    @Async("auditExecutor")
    public void recordEmailVerified(String userId, String email) {
        AuditLog log = base(AuditAction.EMAIL_VERIFIED, userId, email);
        log.setActorType(USER);
        log.setActorUserId(userId);
        log.setActorRoles(Set.of(USER));
        persist(log);
    }

    /** Operação por um usuário autenticado (ator extraído do JWT). */
    @Async("auditExecutor")
    public void recordFromJwt(AuditAction action, Jwt actor, String targetUserId, String targetEmail) {
        AuditLog log = base(action, targetUserId, targetEmail);
        Set<String> roles = rolesOf(actor);
        log.setActorType(roles.contains(ADMIN) ? ADMIN : USER);
        log.setActorUserId(actor != null ? actor.getClaimAsString("userID") : null);
        log.setActorRoles(roles);
        persist(log);
    }

    /**
     * Leitura em massa por um usuário autenticado: grava <b>uma entrada por titular</b> alcançado,
     * num único {@code insert} em lote. Uma entrada agregada (com {@code targetUserId} nulo) não
     * apareceria no histórico de titular algum — que é justamente a consulta que a trilha existe
     * para responder.
     */
    @Async("auditExecutor")
    public void recordBulkFromJwt(AuditAction action, Jwt actor, List<AuditTarget> targets) {
        if (targets.isEmpty()) {
            return;
        }
        Set<String> roles = rolesOf(actor);
        String actorType = roles.contains(ADMIN) ? ADMIN : USER;
        String actorUserId = actor != null ? actor.getClaimAsString("userID") : null;

        List<AuditLog> logs = targets.stream().map(target -> {
            AuditLog log = base(action, target.userId(), target.email());
            log.setActorType(actorType);
            log.setActorUserId(actorUserId);
            log.setActorRoles(roles);
            return log;
        }).toList();

        persistAll(logs);
    }

    /** Titular alcançado por uma leitura em massa. O e-mail é mascarado na montagem do registro. */
    public record AuditTarget(String userId, String email) {}

    /** Operação pelo canal interno (auth-server): ator SYSTEM, sem JWT. */
    @Async("auditExecutor")
    public void recordSystem(AuditAction action, String targetUserId, String targetEmail) {
        AuditLog log = base(action, targetUserId, targetEmail);
        log.setActorType(SYSTEM);
        persist(log);
    }

    private AuditLog base(AuditAction action, String targetUserId, String targetEmail) {
        AuditLog log = new AuditLog();
        Instant now = Instant.now();
        log.setTimestamp(now);
        // Retenção decidida na escrita (ADR-022) — o índice TTL é expire-at, ver AuditLog.purgeAt.
        log.setPurgeAt(now.plus(retention));
        log.setAction(action);
        log.setTargetUserId(targetUserId);
        log.setTargetEmail(targetEmail != null ? LogUtils.maskEmail(targetEmail) : null);
        log.setCorrelationId(MDC.get("traceId"));
        return log;
    }

    private Set<String> rolesOf(Jwt actor) {
        if (actor == null) {
            return Set.of();
        }
        List<String> roles = actor.getClaimAsStringList("roles");
        return roles != null ? Set.copyOf(roles) : Set.of();
    }

    private void persist(AuditLog log) {
        try {
            auditLogRepository.insert(log);
        } catch (RuntimeException e) {
            // Isolamento de falha: a auditoria nunca derruba a operação de negócio.
            LOGGER.error(
                "| AUDIT | falha ao gravar trilha | ação: {}, target: {}",
                log.getAction(),
                log.getTargetUserId(),
                e
            );
        }
    }

    private void persistAll(List<AuditLog> logs) {
        try {
            auditLogRepository.insert(logs);
        } catch (RuntimeException e) {
            // Isolamento de falha: a auditoria nunca derruba a operação de negócio.
            LOGGER.error(
                "| AUDIT | falha ao gravar trilha em lote | ação: {}, entradas: {}",
                logs.get(0).getAction(),
                logs.size(),
                e
            );
        }
    }
}
