package com.users.userservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.users.userservice.domain.AuditAction;
import com.users.userservice.dtos.AdminUserResponseDTO;
import com.users.userservice.dtos.AuditLogResponseDTO;
import com.users.userservice.dtos.UpdateRolesRequestDTO;
import com.users.userservice.services.AdminService;
import com.users.userservice.services.AdminService.RoleUpdateResult;
import com.users.userservice.services.AuditService;
import com.users.userservice.services.EmailVerificationService;
import com.users.userservice.services.RegisterService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Superfície administrativa do user-service (ADR-014): listagem incluindo inativos,
 * consulta da trilha de auditoria LGPD e gestão de roles, além dos 2 deletes
 * administrativos absorvidos do {@code UserController} (ADR-013).
 *
 * <p>Enforcement de {@code ROLE_ADMIN} é exclusivamente via {@code @PreAuthorize} aqui — o
 * gateway não ganha {@code hasRole()} (decisão explícita, consistente com o restante do
 * projeto: autorização por role é sempre downstream).
 */
@Tag(name = "Admin Controller", description = "Endpoints administrativos do serviço de usuários")
@RestController
@RequestMapping(value = "/v1/admin")
public class AdminController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;
    private final RegisterService registerService;
    private final AuditService auditService;
    private final EmailVerificationService emailVerificationService;

    public AdminController(
            AdminService adminService,
            RegisterService registerService,
            AuditService auditService,
            EmailVerificationService emailVerificationService) {
        this.adminService = adminService;
        this.registerService = registerService;
        this.auditService = auditService;
        this.emailVerificationService = emailVerificationService;
    }

    @Operation(summary = "Lista todos os usuários, incluindo inativos, com filtros opcionais")
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AdminUserResponseDTO>> listAllUsers(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            Pageable pageable) {
        LOGGER.info(
            "| GET | ADMIN listagem | página: {}x{}, active: {}",
            pageable.getPageSize(),
            pageable.getPageNumber(),
            active
        );
        Page<AdminUserResponseDTO> users = adminService.listAllUsers(active, name, email, pageable);
        return ResponseEntity.ok(users);
    }

    @Operation(summary = "Consulta a trilha de auditoria LGPD de um titular específico")
    @GetMapping("/users/{id}/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLogResponseDTO>> getAuditLogsByUser(
            @PathVariable("id") String id,
            Pageable pageable) {
        LOGGER.info("| GET | ADMIN auditoria por titular | ID: {}", id);
        Page<AuditLogResponseDTO> logs = adminService.getAuditLogsByUser(id, pageable);
        return ResponseEntity.ok(logs);
    }

    @Operation(summary = "Feed geral da trilha de auditoria LGPD, paginado")
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLogResponseDTO>> getAllAuditLogs(Pageable pageable) {
        LOGGER.info(
            "| GET | ADMIN auditoria geral | página: {}x{}",
            pageable.getPageSize(),
            pageable.getPageNumber()
        );
        Page<AuditLogResponseDTO> logs = adminService.getAllAuditLogs(pageable);
        return ResponseEntity.ok(logs);
    }

    @Operation(summary = "Promove ou revoga roles de um titular (USER/ADMIN)")
    @PatchMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminUserResponseDTO> updateUserRoles(
            @PathVariable("id") String id,
            @RequestBody @Valid UpdateRolesRequestDTO requestDTO,
            @AuthenticationPrincipal Jwt jwt) {
        String requestingAdminId = jwt.getClaim("userID");
        LOGGER.info(
            "| PATCH | ADMIN atualizar roles | ID alvo: {}, ator: {}",
            id,
            requestingAdminId
        );
        RoleUpdateResult result = adminService.updateUserRoles(id, requestDTO.getRoles(), requestingAdminId);

        if (result.adminGranted()) {
            auditService.recordFromJwt(AuditAction.ROLE_GRANT, jwt, id, result.response().getEmail());
        } else if (result.adminRevoked()) {
            auditService.recordFromJwt(AuditAction.ROLE_REVOKE, jwt, id, result.response().getEmail());
        }

        return ResponseEntity.ok(result.response());
    }

    @Operation(summary = "Reenvia o e-mail de verificação de cadastro para um titular")
    @PostMapping("/users/{id}/resend-verification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resendVerification(@PathVariable("id") String id) {
        LOGGER.info("| POST | ADMIN reenviar verificação de e-mail | ID alvo: {}", id);
        emailVerificationService.resendByUserId(id);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Desativa (soft-delete) outro titular")
    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateUser(
            @PathVariable("id") String id,
            @AuthenticationPrincipal Jwt jwt) {
        LOGGER.info("| DELETE | ADMIN soft-delete | ID alvo: {}", id);
        registerService.deactivateUser(id);
        auditService.recordFromJwt(AuditAction.SOFT_DELETE_ADMIN, jwt, id, null);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove definitivamente (hard-delete) outro titular")
    @DeleteMapping("/users/del/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(
            @PathVariable("id") String id,
            @AuthenticationPrincipal Jwt jwt) {
        LOGGER.info("| DELETE | ADMIN hard-delete | ID alvo: {}", id);
        registerService.deleteUser(id);
        auditService.recordFromJwt(AuditAction.HARD_DELETE_ADMIN, jwt, id, null);
        return ResponseEntity.noContent().build();
    }
}
