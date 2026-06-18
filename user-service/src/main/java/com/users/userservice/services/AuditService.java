package com.users.userservice.services;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    public AuditService(IAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
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

    /** Operação pelo canal interno (auth-server): ator SYSTEM, sem JWT. */
    @Async("auditExecutor")
    public void recordSystem(AuditAction action, String targetUserId, String targetEmail) {
        AuditLog log = base(action, targetUserId, targetEmail);
        log.setActorType(SYSTEM);
        persist(log);
    }

    private AuditLog base(AuditAction action, String targetUserId, String targetEmail) {
        AuditLog log = new AuditLog();
        log.setTimestamp(Instant.now());
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
}
