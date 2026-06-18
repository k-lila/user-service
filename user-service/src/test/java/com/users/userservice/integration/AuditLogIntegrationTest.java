package com.users.userservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.users.userservice.domain.AuditAction;
import com.users.userservice.domain.AuditLog;
import com.users.userservice.repository.IAuditLogRepository;
import com.users.userservice.services.AuditService;

/**
 * Integração da trilha de auditoria: persistência real no Mongo (Testcontainers) via
 * {@link AuditService} (escrita síncrona neste contexto — sem proxy @Async) e leitura de volta.
 */
class AuditLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private IAuditLogRepository auditLogRepository;

    @BeforeEach
    void limparAuditLogs() {
        auditLogRepository.deleteAll();
    }

    @Test
    void deveGravarERecuperarEntradaDeAuditoria() {
        // Escrita assíncrona (@Async): aguarda a persistência ficar visível.
        auditService.recordSystem(AuditAction.READ_INTERNAL_CREDENTIAL, "target-1", "fulano@email.com");

        await().atMost(Duration.ofSeconds(5))
                .until(() -> auditLogRepository.count() == 1);
        AuditLog saved = auditLogRepository.findAll().get(0);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAction()).isEqualTo(AuditAction.READ_INTERNAL_CREDENTIAL);
        assertThat(saved.getActorType()).isEqualTo("SYSTEM");
        assertThat(saved.getTargetUserId()).isEqualTo("target-1");
        // E-mail persiste mascarado (LGPD).
        assertThat(saved.getTargetEmail()).isEqualTo("f***@email.com");
        assertThat(saved.getTimestamp()).isNotNull();
    }

    @Test
    void deveSerColecaoAppendOnly_preservandoEntradasDistintas() {
        AuditLog manual = new AuditLog();
        manual.setTimestamp(Instant.now());
        manual.setAction(AuditAction.REGISTER);
        manual.setActorType("USER");
        manual.setActorUserId("u1");
        manual.setActorRoles(Set.of("USER"));
        manual.setTargetUserId("u1");
        auditLogRepository.insert(manual);

        auditService.recordSystem(AuditAction.READ_INTERNAL_CREDENTIAL, "u2", "x@y.com");

        await().atMost(Duration.ofSeconds(5))
                .until(() -> auditLogRepository.count() == 2);
    }
}
