package com.users.userservice.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.users.userservice.config.SecurityConfig;
import com.users.userservice.domain.AuditAction;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.AuthDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.GlobalExceptionHandler;
import com.users.userservice.services.AuditService;
import com.users.userservice.services.AuthenticationService;

@WebMvcTest(InternalUserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.tracing.sampling.probability=0",
        "internal.api.token=test-internal-token"
})
class InternalUserControllerTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired MockMvc mockMvc;

    @MockitoBean AuthenticationService authenticationService;
    @MockitoBean AuditService auditService;
    @MockitoBean JwtDecoder jwtDecoder;

    private AuthDTO buildAuthDTO() {
        AuthDTO dto = new AuthDTO();
        dto.setId("user-id-123");
        dto.setEmail("fulano@email.com");
        dto.setPasswordHash("$2a$10$hashed");
        dto.setActive(true);
        dto.setRoles(Set.of("USER"));
        dto.setEmailVerified(true);
        return dto;
    }

    @Test
    void findByEmail_deveRetornar200ComAuthDTO_quandoUsuarioAtivo() throws Exception {
        when(authenticationService.getUserByEmail("fulano@email.com")).thenReturn(buildAuthDTO());

        mockMvc.perform(get("/internal/users/email/{email}", "fulano@email.com")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("fulano@email.com"))
                .andExpect(jsonPath("$.passwordHash").value("$2a$10$hashed"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.emailVerified").value(true));

        verify(auditService).recordSystem(AuditAction.READ_INTERNAL_CREDENTIAL, "user-id-123", "fulano@email.com");
    }

    @Test
    void findByEmail_deveRetornarEmailVerifiedFalse_quandoUsuarioComEmailNaoVerificado() throws Exception {
        AuthDTO dto = buildAuthDTO();
        dto.setEmailVerified(false);
        when(authenticationService.getUserByEmail("fulano@email.com")).thenReturn(dto);

        mockMvc.perform(get("/internal/users/email/{email}", "fulano@email.com")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void findByEmail_deveRetornar404_quandoUsuarioNaoExiste() throws Exception {
        when(authenticationService.getUserByEmail("nao@existe.com"))
                .thenThrow(new DomainEntityNotFound(User.class, "email", "nao@existe.com"));

        mockMvc.perform(get("/internal/users/email/{email}", "nao@existe.com")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void findByEmail_deveRetornar403_semToken() throws Exception {
        mockMvc.perform(get("/internal/users/email/{email}", "fulano@email.com"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authenticationService);
    }

    @Test
    void findByEmail_deveRetornar403_comTokenInvalido() throws Exception {
        mockMvc.perform(get("/internal/users/email/{email}", "fulano@email.com")
                        .header("X-Internal-Token", "token-errado"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authenticationService);
    }
}
