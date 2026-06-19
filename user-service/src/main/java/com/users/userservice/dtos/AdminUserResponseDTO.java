package com.users.userservice.dtos;

import java.util.List;
import java.util.Set;

import com.users.userservice.domain.User;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO de resposta administrativa — campos de {@link UserResponseDTO} + {@code roles}.
 *
 * <p>Usado <b>somente</b> pelo {@code AdminController} (listagem e PATCH de roles).
 * {@link UserResponseDTO} (usado por {@code UserController}) permanece inalterado, sem
 * {@code roles} — esta superfície é ADMIN-only, então expor {@code roles} aqui não amplia
 * exposição indevida (e é necessário: sem isso a gestão de roles seria às ciegas).
 */
@Getter
@Setter
public class AdminUserResponseDTO {
    String id;
    String name;
    String email;
    String registrationDate;
    Boolean active;
    String consentAcceptedAt;
    String termsVersion;
    List<String> tenantIds;
    Boolean emailVerified;
    String emailVerifiedAt;
    Set<String> roles;

    public static AdminUserResponseDTO toResponseDTO(User user) {
        AdminUserResponseDTO dto = new AdminUserResponseDTO();
        dto.setId(user.getId());
        dto.setName(user.getName());
        dto.setEmail(user.getEmail());
        dto.setRegistrationDate(user.getRegistrationDate().toString());
        dto.setActive(user.getActive());
        if (user.getConsentAcceptedAt() != null) {
            dto.setConsentAcceptedAt(user.getConsentAcceptedAt().toString());
        }
        dto.setTermsVersion(user.getTermsVersion());
        dto.setTenantIds(user.getTenantIds());
        // emailVerified nulo (legado, anterior ao campo) é tratado como verificado.
        dto.setEmailVerified(!Boolean.FALSE.equals(user.getEmailVerified()));
        if (user.getEmailVerifiedAt() != null) {
            dto.setEmailVerifiedAt(user.getEmailVerifiedAt().toString());
        }
        dto.setRoles(user.getRoles());
        return dto;
    }
}
