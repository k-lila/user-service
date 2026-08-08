package com.users.userservice.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import com.users.userservice.domain.AuditAction;
import com.users.userservice.domain.AuditLog;
import com.users.userservice.repository.IAuditLogRepository;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private IAuditLogRepository auditLogRepository;

    private static final Duration RETENCAO = Duration.ofDays(180);

    private AuditService service() {
        return new AuditService(auditLogRepository, RETENCAO);
    }

    private Jwt jwt(String userID, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userID", userID)
                .claim("roles", roles)
                .build();
    }

    private AuditLog captureSaved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordRegistration_deveGravarAcaoRegisterComAtorUsuarioEEmailMascarado() {
        service().recordRegistration("new-id", "fulano@email.com");

        AuditLog log = captureSaved();
        assertThat(log.getAction()).isEqualTo(AuditAction.REGISTER);
        assertThat(log.getActorType()).isEqualTo("USER");
        assertThat(log.getActorUserId()).isEqualTo("new-id");
        assertThat(log.getActorRoles()).containsExactly("USER");
        assertThat(log.getTargetUserId()).isEqualTo("new-id");
        assertThat(log.getTargetEmail()).isEqualTo("f***@email.com");
        assertThat(log.getTimestamp()).isNotNull();
    }

    @Test
    void recordFromJwt_deveMarcarActorTypeAdmin_quandoRolesContemAdmin() {
        service().recordFromJwt(AuditAction.HARD_DELETE_ADMIN, jwt("admin-id", List.of("USER", "ADMIN")), "target-id", null);

        AuditLog log = captureSaved();
        assertThat(log.getAction()).isEqualTo(AuditAction.HARD_DELETE_ADMIN);
        assertThat(log.getActorType()).isEqualTo("ADMIN");
        assertThat(log.getActorUserId()).isEqualTo("admin-id");
        assertThat(log.getTargetUserId()).isEqualTo("target-id");
        assertThat(log.getTargetEmail()).isNull();
    }

    @Test
    void recordFromJwt_deveMarcarActorTypeUser_quandoSemAdmin() {
        service().recordFromJwt(AuditAction.UPDATE, jwt("user-id", List.of("USER")), "target-id", "alvo@email.com");

        AuditLog log = captureSaved();
        assertThat(log.getActorType()).isEqualTo("USER");
        assertThat(log.getTargetEmail()).isEqualTo("a***@email.com");
    }

    @Test
    void recordSystem_deveMarcarActorTypeSystemSemAtor() {
        service().recordSystem(AuditAction.READ_INTERNAL_CREDENTIAL, "target-id", "alvo@email.com");

        AuditLog log = captureSaved();
        assertThat(log.getAction()).isEqualTo(AuditAction.READ_INTERNAL_CREDENTIAL);
        assertThat(log.getActorType()).isEqualTo("SYSTEM");
        assertThat(log.getActorUserId()).isNull();
        assertThat(log.getActorRoles()).isNull();
    }

    @Test
    void recordBulkFromJwt_deveGravarUmaEntradaPorTitular_numUnicoLote() {
        service().recordBulkFromJwt(
                AuditAction.ADMIN_LIST_USERS,
                jwt("admin-id", List.of("USER", "ADMIN")),
                List.of(
                        new AuditService.AuditTarget("alvo-1", "primeiro@email.com"),
                        new AuditService.AuditTarget("alvo-2", "segundo@email.com")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).insert(captor.capture());

        List<AuditLog> logs = captor.getValue();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getAction()).isEqualTo(AuditAction.ADMIN_LIST_USERS);
            assertThat(log.getActorType()).isEqualTo("ADMIN");
            assertThat(log.getActorUserId()).isEqualTo("admin-id");
            assertThat(log.getTimestamp()).isNotNull();
        });
        // targetUserId preenchido é o que faz a listagem aparecer no histórico de cada titular.
        assertThat(logs).extracting(AuditLog::getTargetUserId).containsExactly("alvo-1", "alvo-2");
        assertThat(logs).extracting(AuditLog::getTargetEmail)
                .containsExactly("p***@email.com", "s***@email.com");
    }

    /**
     * Retenção da trilha (ADR-022). O prazo é decidido na escrita, não no índice: é o que permite
     * mudar {@code app.audit.retention} sem {@code collMod} na coleção.
     */
    @Test
    void base_deveGravarPurgeAtUmaRetencaoAdianteDoTimestamp() {
        service().recordRegistration("new-id", "fulano@email.com");

        AuditLog log = captureSaved();
        assertThat(log.getPurgeAt()).isEqualTo(log.getTimestamp().plus(RETENCAO));
    }

    @Test
    void recordBulkFromJwt_deveGravarPurgeAtEmTodasAsEntradasDoLote() {
        service().recordBulkFromJwt(
                AuditAction.ADMIN_LIST_USERS,
                jwt("admin-id", List.of("ADMIN")),
                List.of(
                        new AuditService.AuditTarget("alvo-1", "primeiro@email.com"),
                        new AuditService.AuditTarget("alvo-2", "segundo@email.com")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).insert(captor.capture());

        // Uma entrada sem purgeAt é ignorada pelo TTL do Mongo e fica para sempre — e a listagem
        // é justamente o caminho que grava até 100 entradas por requisição.
        assertThat(captor.getValue()).allSatisfy(log ->
                assertThat(log.getPurgeAt()).isEqualTo(log.getTimestamp().plus(RETENCAO)));
    }

    @Test
    void recordBulkFromJwt_naoDeveTocarORepositorio_quandoPaginaVazia() {
        service().recordBulkFromJwt(AuditAction.ADMIN_LIST_USERS, jwt("admin-id", List.of("ADMIN")), List.of());

        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void recordBulkFromJwt_naoDevePropagarExcecao_quandoRepositorioFalha() {
        doThrow(new RuntimeException("mongo down")).when(auditLogRepository).insert(anyList());

        assertThatCode(() -> service().recordBulkFromJwt(
                AuditAction.ADMIN_LIST_USERS,
                jwt("admin-id", List.of("ADMIN")),
                List.of(new AuditService.AuditTarget("alvo-1", "primeiro@email.com"))))
                .doesNotThrowAnyException();
    }

    @Test
    void persist_naoDevePropagarExcecao_quandoRepositorioFalha() {
        doThrow(new RuntimeException("mongo down")).when(auditLogRepository).insert(any(AuditLog.class));

        // Isolamento de falha: a auditoria nunca derruba a operação de negócio.
        assertThatCode(() -> service().recordRegistration("new-id", "fulano@email.com"))
                .doesNotThrowAnyException();
    }
}
