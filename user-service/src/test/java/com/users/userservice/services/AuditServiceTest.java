package com.users.userservice.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

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

    private AuditService service() {
        return new AuditService(auditLogRepository);
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
    void persist_naoDevePropagarExcecao_quandoRepositorioFalha() {
        doThrow(new RuntimeException("mongo down")).when(auditLogRepository).insert(any(AuditLog.class));

        // Isolamento de falha: a auditoria nunca derruba a operação de negócio.
        assertThatCode(() -> service().recordRegistration("new-id", "fulano@email.com"))
                .doesNotThrowAnyException();
    }
}
