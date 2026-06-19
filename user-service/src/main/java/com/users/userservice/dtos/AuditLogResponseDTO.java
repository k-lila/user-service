package com.users.userservice.dtos;

import java.util.Set;

import com.users.userservice.domain.AuditAction;
import com.users.userservice.domain.AuditLog;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO de resposta para a consulta administrativa da trilha de auditoria LGPD
 * (`GET /v1/admin/users/{id}/audit-logs`, `GET /v1/admin/audit-logs`). Não expõe a
 * entidade {@link AuditLog} crua.
 */
@Getter
@Setter
public class AuditLogResponseDTO {
    String id;
    String timestamp;
    AuditAction action;
    String actorType;
    String actorUserId;
    Set<String> actorRoles;
    String targetUserId;
    String targetEmail;
    String correlationId;

    public static AuditLogResponseDTO toResponseDTO(AuditLog log) {
        AuditLogResponseDTO dto = new AuditLogResponseDTO();
        dto.setId(log.getId());
        dto.setTimestamp(log.getTimestamp() != null ? log.getTimestamp().toString() : null);
        dto.setAction(log.getAction());
        dto.setActorType(log.getActorType());
        dto.setActorUserId(log.getActorUserId());
        dto.setActorRoles(log.getActorRoles());
        dto.setTargetUserId(log.getTargetUserId());
        dto.setTargetEmail(log.getTargetEmail());
        dto.setCorrelationId(log.getCorrelationId());
        return dto;
    }
}
