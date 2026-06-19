package com.users.userservice.dtos;

import java.util.Set;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * Payload de {@code PATCH /v1/admin/users/{id}/roles}. A validação de conjunto permitido
 * ({@code {USER, ADMIN}}) e a regra "USER sempre presente" (rejeitar, não normalizar) vivem
 * em {@code AdminService.updateUserRoles} — ver ADR-014.
 */
@Getter
@Setter
public class UpdateRolesRequestDTO {

    @NotEmpty
    private Set<String> roles;
}
