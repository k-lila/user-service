package com.users.userservice.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.users.userservice.config.SecurityConfig;
import com.users.userservice.domain.AuditAction;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.AdminUserResponseDTO;
import com.users.userservice.dtos.AuditLogResponseDTO;
import com.users.userservice.dtos.UpdateRolesRequestDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.GlobalExceptionHandler;
import com.users.userservice.exceptions.SelfRoleRevocationException;
import com.users.userservice.services.AdminService;
import com.users.userservice.services.AdminService.RoleUpdateResult;
import com.users.userservice.services.AuditService;
import com.users.userservice.services.EmailVerificationService;
import com.users.userservice.services.RegisterService;

@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.tracing.sampling.probability=0",
        "internal.api.token=test-internal-token"
})
class AdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminService adminService;
    @MockitoBean RegisterService registerService;
    @MockitoBean AuditService auditService;
    @MockitoBean EmailVerificationService emailVerificationService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String ADMIN_ID = "admin-id-1";
    private static final String TARGET_ID = "target-id-1";

    private AdminUserResponseDTO buildAdminUserResponse(Set<String> roles) {
        AdminUserResponseDTO dto = new AdminUserResponseDTO();
        dto.setId(TARGET_ID);
        dto.setName("Fulano");
        dto.setEmail("fulano@email.com");
        dto.setRegistrationDate("2026-01-01T00:00:00Z");
        dto.setActive(true);
        dto.setRoles(roles);
        return dto;
    }

    private AuditLogResponseDTO buildAuditLog() {
        AuditLogResponseDTO dto = new AuditLogResponseDTO();
        dto.setId("log-1");
        dto.setAction(AuditAction.UPDATE);
        dto.setTargetUserId(TARGET_ID);
        return dto;
    }

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    // ── GET /v1/admin/users ──────────────────────────────────────────────────

    @Test
    void listAllUsers_deveRetornar200_quandoTokenComRoleAdmin() throws Exception {
        when(adminService.listAllUsers(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(buildAdminUserResponse(Set.of("USER")))));

        mockMvc.perform(get("/v1/admin/users")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void listAllUsers_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(get("/v1/admin/users")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }

    @Test
    void listAllUsers_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAllUsers_deveRepassarFiltros() throws Exception {
        when(adminService.listAllUsers(eq(false), eq("Maria"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(buildAdminUserResponse(Set.of("USER")))));

        mockMvc.perform(get("/v1/admin/users?active=false&name=Maria")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        verify(adminService).listAllUsers(eq(false), eq("Maria"), any(), any());
    }

    // ── GET /v1/admin/users/{id} (leitura admin, ADR-016 — fix G1) ────────────

    @Test
    void findById_deveRetornar200ComRoles_quandoTokenComRoleAdmin() throws Exception {
        when(adminService.findById(TARGET_ID)).thenReturn(buildAdminUserResponse(Set.of("USER", "ADMIN")));

        mockMvc.perform(get("/v1/admin/users/{id}", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TARGET_ID))
                .andExpect(jsonPath("$.email").value("fulano@email.com"))
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("USER", "ADMIN")));
    }

    @Test
    void findById_deveAuditarAdminReadUser() throws Exception {
        when(adminService.findById(TARGET_ID)).thenReturn(buildAdminUserResponse(Set.of("USER")));

        mockMvc.perform(get("/v1/admin/users/{id}", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        verify(auditService).recordFromJwt(eq(AuditAction.ADMIN_READ_USER), any(), eq(TARGET_ID), eq("fulano@email.com"));
    }

    @Test
    void findById_deveRetornar404_quandoTitularNaoExiste() throws Exception {
        when(adminService.findById("inexistente"))
                .thenThrow(new DomainEntityNotFound(User.class, "ID", "inexistente"));

        mockMvc.perform(get("/v1/admin/users/{id}", "inexistente")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void findById_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(get("/v1/admin/users/{id}", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }

    @Test
    void findById_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/admin/users/{id}", TARGET_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminService);
    }

    // ── GET /v1/admin/users/email/{email} (leitura admin, ADR-016 — fix G1) ───

    @Test
    void findByEmail_deveRetornar200ComRoles_quandoTokenComRoleAdmin() throws Exception {
        when(adminService.findByEmail("fulano@email.com")).thenReturn(buildAdminUserResponse(Set.of("USER")));

        mockMvc.perform(get("/v1/admin/users/email/{email}", "fulano@email.com")
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("fulano@email.com"))
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("USER")));
    }

    @Test
    void findByEmail_deveAuditarAdminReadUser() throws Exception {
        when(adminService.findByEmail("fulano@email.com")).thenReturn(buildAdminUserResponse(Set.of("USER")));

        mockMvc.perform(get("/v1/admin/users/email/{email}", "fulano@email.com")
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        verify(auditService).recordFromJwt(eq(AuditAction.ADMIN_READ_USER), any(), eq(TARGET_ID), eq("fulano@email.com"));
    }

    @Test
    void findByEmail_deveRetornar404_quandoTitularNaoExiste() throws Exception {
        when(adminService.findByEmail("nao@existe.com"))
                .thenThrow(new DomainEntityNotFound(User.class, "Email", "nao@existe.com"));

        mockMvc.perform(get("/v1/admin/users/email/{email}", "nao@existe.com")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void findByEmail_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(get("/v1/admin/users/email/{email}", "fulano@email.com")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }

    @Test
    void findByEmail_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/admin/users/email/{email}", "fulano@email.com"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminService);
    }

    // ── GET /v1/admin/users/{id}/audit-logs ──────────────────────────────────

    @Test
    void getAuditLogsByUser_deveRetornar200_quandoTitularExiste() throws Exception {
        when(adminService.getAuditLogsByUser(eq(TARGET_ID), any()))
                .thenReturn(new PageImpl<>(List.of(buildAuditLog())));

        mockMvc.perform(get("/v1/admin/users/{id}/audit-logs", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void getAuditLogsByUser_deveRetornar404_quandoTitularNaoExiste() throws Exception {
        when(adminService.getAuditLogsByUser(eq("inexistente"), any()))
                .thenThrow(new DomainEntityNotFound(User.class, "ID", "inexistente"));

        mockMvc.perform(get("/v1/admin/users/{id}/audit-logs", "inexistente")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAuditLogsByUser_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(get("/v1/admin/users/{id}/audit-logs", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }

    @Test
    void getAuditLogsByUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/admin/users/{id}/audit-logs", TARGET_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminService);
    }

    @Test
    void getAuditLogsByUser_deveRepassarPageableDaQueryString() throws Exception {
        when(adminService.getAuditLogsByUser(eq(TARGET_ID), any()))
                .thenReturn(new PageImpl<>(List.of(buildAuditLog())));

        mockMvc.perform(get("/v1/admin/users/{id}/audit-logs?page=2&size=50", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(adminService).getAuditLogsByUser(eq(TARGET_ID), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(2, captor.getValue().getPageNumber());
        org.junit.jupiter.api.Assertions.assertEquals(50, captor.getValue().getPageSize());
    }

    // ── GET /v1/admin/audit-logs ──────────────────────────────────────────────

    @Test
    void getAllAuditLogs_deveRetornar200() throws Exception {
        when(adminService.getAllAuditLogs(any())).thenReturn(new PageImpl<>(List.of(buildAuditLog())));

        mockMvc.perform(get("/v1/admin/audit-logs")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void getAllAuditLogs_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(get("/v1/admin/audit-logs")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }

    @Test
    void getAllAuditLogs_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/admin/audit-logs"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminService);
    }

    // ── PATCH /v1/admin/users/{id}/roles ─────────────────────────────────────

    @Test
    void updateUserRoles_deveRetornar200EAuditarRoleGrant_quandoPromocao() throws Exception {
        AdminUserResponseDTO response = buildAdminUserResponse(Set.of("USER", "ADMIN"));
        when(adminService.updateUserRoles(eq(TARGET_ID), eq(Set.of("USER", "ADMIN")), eq(ADMIN_ID)))
                .thenReturn(new RoleUpdateResult(response, true, false));

        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER", "ADMIN"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("USER", "ADMIN")));

        verify(auditService).recordFromJwt(eq(AuditAction.ROLE_GRANT), any(), eq(TARGET_ID), eq("fulano@email.com"));
        verify(auditService, never()).recordFromJwt(eq(AuditAction.ROLE_REVOKE), any(), any(), any());
    }

    @Test
    void updateUserRoles_deveRetornar200EAuditarRoleRevoke_quandoRevogacaoDeTerceiro() throws Exception {
        AdminUserResponseDTO response = buildAdminUserResponse(Set.of("USER"));
        when(adminService.updateUserRoles(eq(TARGET_ID), eq(Set.of("USER")), eq(ADMIN_ID)))
                .thenReturn(new RoleUpdateResult(response, false, true));

        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isOk());

        verify(auditService).recordFromJwt(eq(AuditAction.ROLE_REVOKE), any(), eq(TARGET_ID), eq("fulano@email.com"));
        verify(auditService, never()).recordFromJwt(eq(AuditAction.ROLE_GRANT), any(), any(), any());
    }

    @Test
    void updateUserRoles_naoDeveAuditar_quandoNaoHaTransicaoEmAdmin() throws Exception {
        AdminUserResponseDTO response = buildAdminUserResponse(Set.of("USER"));
        when(adminService.updateUserRoles(eq(TARGET_ID), eq(Set.of("USER")), eq(ADMIN_ID)))
                .thenReturn(new RoleUpdateResult(response, false, false));

        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isOk());

        verifyNoInteractions(auditService);
    }

    @Test
    void updateUserRoles_deveRetornar409_quandoAutoRevogacaoBloqueada() throws Exception {
        when(adminService.updateUserRoles(eq(ADMIN_ID), eq(Set.of("USER")), eq(ADMIN_ID)))
                .thenThrow(new SelfRoleRevocationException(ADMIN_ID));

        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", ADMIN_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isConflict());

        verify(auditService, never()).recordFromJwt(eq(AuditAction.ROLE_REVOKE), any(), any(), any());
    }

    @Test
    void updateUserRoles_deveRetornar400_quandoPayloadSemUser() throws Exception {
        when(adminService.updateUserRoles(eq(TARGET_ID), eq(Set.of("ADMIN")), eq(ADMIN_ID)))
                .thenThrow(new IllegalArgumentException("roles must contain USER"));

        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("ADMIN"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(auditService);
    }

    @Test
    void updateUserRoles_deveRetornar400_quandoRoleForaDoConjuntoPermitido() throws Exception {
        when(adminService.updateUserRoles(eq(TARGET_ID), eq(Set.of("USER", "SUPERADMIN")), eq(ADMIN_ID)))
                .thenThrow(new IllegalArgumentException("role not allowed: SUPERADMIN"));

        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER", "SUPERADMIN"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateUserRoles_deveRetornar404_quandoAlvoNaoExiste() throws Exception {
        when(adminService.updateUserRoles(eq("inexistente"), eq(Set.of("USER")), eq(ADMIN_ID)))
                .thenThrow(new DomainEntityNotFound(User.class, "ID", "inexistente"));

        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", "inexistente")
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateUserRoles_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
        verifyNoInteractions(auditService);
    }

    @Test
    void updateUserRoles_deveRetornar401_quandoSemToken() throws Exception {
        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of("USER"));

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminService);
        verifyNoInteractions(auditService);
    }

    @Test
    void updateUserRoles_deveRetornar400_quandoRolesVazio() throws Exception {
        UpdateRolesRequestDTO request = new UpdateRolesRequestDTO();
        request.setRoles(Set.of());

        mockMvc.perform(patch("/v1/admin/users/{id}/roles", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminService);
    }

    // ── POST /v1/admin/users/{id}/resend-verification ────────────────────────

    @Test
    void resendVerification_deveRetornar202() throws Exception {
        mockMvc.perform(post("/v1/admin/users/{id}/resend-verification", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isAccepted());

        verify(emailVerificationService).resendByUserId(TARGET_ID);
    }

    @Test
    void resendVerification_deveRetornar404_quandoAlvoNaoExiste() throws Exception {
        doThrow(new DomainEntityNotFound(User.class, "ID", "inexistente"))
                .when(emailVerificationService).resendByUserId("inexistente");

        mockMvc.perform(post("/v1/admin/users/{id}/resend-verification", "inexistente")
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void resendVerification_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(post("/v1/admin/users/{id}/resend-verification", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    void resendVerification_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(post("/v1/admin/users/{id}/resend-verification", TARGET_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /v1/admin/users/{id} ──────────────────────────────────────────

    @Test
    void deactivateUser_deveRetornar204EAuditarSoftDeleteAdmin() throws Exception {
        mockMvc.perform(delete("/v1/admin/users/{id}", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());

        verify(registerService).deactivateUser(TARGET_ID);
        verify(auditService).recordFromJwt(eq(AuditAction.SOFT_DELETE_ADMIN), any(), eq(TARGET_ID), isNull());
    }

    @Test
    void deactivateUser_deveRetornar404_quandoAlvoNaoExiste() throws Exception {
        doThrow(new DomainEntityNotFound(User.class, "ID", "inexistente"))
                .when(registerService).deactivateUser("inexistente");

        mockMvc.perform(delete("/v1/admin/users/{id}", "inexistente")
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());

        verify(auditService, never()).recordFromJwt(eq(AuditAction.SOFT_DELETE_ADMIN), any(), any(), any());
    }

    @Test
    void deactivateUser_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(delete("/v1/admin/users/{id}", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(registerService);
        verifyNoInteractions(auditService);
    }

    @Test
    void deactivateUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(delete("/v1/admin/users/{id}", TARGET_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /v1/admin/users/del/{id} ──────────────────────────────────────

    @Test
    void deleteUser_deveRetornar204EAuditarHardDeleteAdmin() throws Exception {
        mockMvc.perform(delete("/v1/admin/users/del/{id}", TARGET_ID)
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());

        verify(registerService).deleteUser(TARGET_ID);
        verify(auditService).recordFromJwt(eq(AuditAction.HARD_DELETE_ADMIN), any(), eq(TARGET_ID), isNull());
    }

    @Test
    void deleteUser_deveRetornar404_quandoAlvoNaoExiste() throws Exception {
        doThrow(new DomainEntityNotFound(User.class, "ID", "inexistente"))
                .when(registerService).deleteUser("inexistente");

        mockMvc.perform(delete("/v1/admin/users/del/{id}", "inexistente")
                .with(jwt()
                        .jwt(b -> b.claim("userID", ADMIN_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());

        verify(auditService, never()).recordFromJwt(eq(AuditAction.HARD_DELETE_ADMIN), any(), any(), any());
    }

    @Test
    void deleteUser_deveRetornar403_quandoTokenSemRoleAdmin() throws Exception {
        mockMvc.perform(delete("/v1/admin/users/del/{id}", TARGET_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(registerService);
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(delete("/v1/admin/users/del/{id}", TARGET_ID))
                .andExpect(status().isUnauthorized());
    }
}
