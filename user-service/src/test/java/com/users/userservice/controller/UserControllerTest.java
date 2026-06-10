package com.users.userservice.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

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

import com.users.userservice.config.SecurityConfig;
import com.users.userservice.exceptions.GlobalExceptionHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.EmailAlreadyRegisteredException;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.tracing.sampling.probability=0",
        "internal.api.token=test-internal-token"
})
class UserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean RegisterService registerService;
    @MockitoBean SearchService searchService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String USER_ID = "user-id-123";

    private UserResponseDTO buildResponse() {
        UserResponseDTO dto = new UserResponseDTO();
        dto.setId(USER_ID);
        dto.setName("Fulano");
        dto.setEmail("fulano@email.com");
        dto.setRegistrationDate("2026-01-01T00:00:00Z");
        dto.setActive(true);
        return dto;
    }

    private UserRequestDTO buildRequest(String name, String email, String password) {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName(name);
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    // ── POST /users/register ─────────────────────────────────────────────────

    @Test
    void register_deveRetornar201_quandoDadosValidos() throws Exception {
        when(registerService.registerUser(any())).thenReturn(buildResponse());

        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "senha123"))))
                .andExpect(status().isCreated());
    }

    @Test
    void register_deveRetornar409_quandoEmailJaCadastrado() throws Exception {
        when(registerService.registerUser(any()))
                .thenThrow(new EmailAlreadyRegisteredException("fulano@email.com"));

        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Ciclano", "fulano@email.com", "senha456"))))
                .andExpect(status().isConflict());
    }

    // ── POST /users/register — validação de corpo (@Valid → 400, C3) ───────────

    @Test
    void register_deveRetornar400_quandoEmailInvalido() throws Exception {
        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "email-invalido", "senha123"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registerService);
    }

    @Test
    void register_deveRetornar400_quandoSenhaMenorQue8() throws Exception {
        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "123"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registerService);
    }

    @Test
    void register_deveRetornar400_quandoNomeEmBranco() throws Exception {
        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("", "fulano@email.com", "senha123"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registerService);
    }

    @Test
    void register_deveRetornar400_quandoSenhaAusente() throws Exception {
        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", null))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registerService);
    }

    @Test
    void register_deveRetornar400_quandoSenhaSemNumero() throws Exception {
        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "somenteletras"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registerService);
    }

    @Test
    void register_deveRetornar400_quandoSenhaSemLetra() throws Exception {
        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "123456789"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registerService);
    }

    // ── GET /users ───────────────────────────────────────────────────────────

    @Test
    void searchAll_deveRetornar200_quandoTokenComRoleUser() throws Exception {
        when(searchService.searchAll(any())).thenReturn(new PageImpl<>(List.of(buildResponse())));

        mockMvc.perform(get("/v1/users")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());
    }

    @Test
    void searchAll_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchAll_deveRetornar403_quandoTokenSemRoleUser() throws Exception {
        mockMvc.perform(get("/v1/users")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER"))))
                .andExpect(status().isForbidden());
    }

    // ── GET /users/{id} ──────────────────────────────────────────────────────

    @Test
    void searchById_deveRetornar200ComBody_quandoIdExiste() throws Exception {
        when(searchService.searchById(USER_ID)).thenReturn(buildResponse());

        mockMvc.perform(get("/v1/users/{id}", USER_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID))
                .andExpect(jsonPath("$.email").value("fulano@email.com"));
    }

    @Test
    void searchById_deveRetornar404_quandoIdNaoExiste() throws Exception {
        when(searchService.searchById("id-inexistente"))
                .thenThrow(new DomainEntityNotFound(User.class, "ID", "id-inexistente"));

        mockMvc.perform(get("/v1/users/{id}", "id-inexistente")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchById_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/users/{id}", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchById_deveRetornar403_quandoTokenSemRoleUser() throws Exception {
        mockMvc.perform(get("/v1/users/{id}", USER_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER"))))
                .andExpect(status().isForbidden());
    }

    // ── GET /users/email/{email} ─────────────────────────────────────────────

    @Test
    void searchByEmail_deveRetornar200ComBody_quandoEmailExiste() throws Exception {
        when(searchService.searchByEmail("fulano@email.com")).thenReturn(buildResponse());

        mockMvc.perform(get("/v1/users/email/{email}", "fulano@email.com")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("fulano@email.com"));
    }

    @Test
    void searchByEmail_deveRetornar404_quandoEmailNaoExiste() throws Exception {
        when(searchService.searchByEmail("nao@existe.com"))
                .thenThrow(new DomainEntityNotFound(User.class, "Email", "nao@existe.com"));

        mockMvc.perform(get("/v1/users/email/{email}", "nao@existe.com")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchByEmail_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/users/email/{email}", "fulano@email.com"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchByEmail_deveRetornar403_quandoTokenSemRoleUser() throws Exception {
        mockMvc.perform(get("/v1/users/email/{email}", "fulano@email.com")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER"))))
                .andExpect(status().isForbidden());
    }

    // ── PUT /users ───────────────────────────────────────────────────────────

    @Test
    void updateUser_deveRetornar200ComBody_quandoAtualizacaoBemSucedida() throws Exception {
        when(registerService.updateUser(any(), eq(USER_ID))).thenReturn(buildResponse());

        mockMvc.perform(put("/v1/users")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Novo Nome", "fulano@email.com", "senha12345"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fulano"));
    }

    @Test
    void updateUser_devePassarUserIdDoJwtParaOServico() throws Exception {
        when(registerService.updateUser(any(), eq(USER_ID))).thenReturn(buildResponse());

        mockMvc.perform(put("/v1/users")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "senha12345"))))
                .andExpect(status().isOk());

        verify(registerService).updateUser(any(), eq(USER_ID));
    }

    @Test
    void updateUser_deveRetornar404_quandoIdNaoExiste() throws Exception {
        when(registerService.updateUser(any(), eq(USER_ID)))
                .thenThrow(new DomainEntityNotFound(User.class, "ID", USER_ID));

        mockMvc.perform(put("/v1/users")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "senha12345"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateUser_deveRetornar409_quandoEmailConflita() throws Exception {
        when(registerService.updateUser(any(), eq(USER_ID)))
                .thenThrow(new EmailAlreadyRegisteredException("outro@email.com"));

        mockMvc.perform(put("/v1/users")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "outro@email.com", "senha12345"))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateUser_deveRetornar200_quandoSenhaAusente() throws Exception {
        when(registerService.updateUser(any(), eq(USER_ID))).thenReturn(buildResponse());

        mockMvc.perform(put("/v1/users")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", null))))
                .andExpect(status().isOk());
    }

    @Test
    void updateUser_deveRetornar400_quandoSenhaInvalida() throws Exception {
        mockMvc.perform(put("/v1/users")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "123"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registerService);
    }

    @Test
    void updateUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(put("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateUser_deveRetornar403_quandoTokenSemRoleUser() throws Exception {
        mockMvc.perform(put("/v1/users")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "senha12345"))))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /users/{id} (soft-delete, ADMIN) ──────────────────────────────

    @Test
    void removeUser_deveRetornar204_quandoIdExiste() throws Exception {
        mockMvc.perform(delete("/v1/users/{id}", USER_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());

        verify(registerService).deactivateUser(USER_ID);
    }

    @Test
    void removeUser_deveRetornar404_quandoIdNaoExiste() throws Exception {
        doThrow(new DomainEntityNotFound(User.class, "ID", "id-inexistente"))
                .when(registerService).deactivateUser("id-inexistente");

        mockMvc.perform(delete("/v1/users/{id}", "id-inexistente")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(delete("/v1/users/{id}", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeUser_deveRetornar403_quandoTokenComRoleUser() throws Exception {
        mockMvc.perform(delete("/v1/users/{id}", USER_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /users/del/{id} (hard-delete, ADMIN) ──────────────────────────

    @Test
    void deleteUser_deveRetornar204_quandoIdExiste() throws Exception {
        mockMvc.perform(delete("/v1/users/del/{id}", USER_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());

        verify(registerService).deleteUser(USER_ID);
    }

    @Test
    void deleteUser_deveRetornar404_quandoIdNaoExiste() throws Exception {
        doThrow(new DomainEntityNotFound(User.class, "ID", "id-inexistente"))
                .when(registerService).deleteUser("id-inexistente");

        mockMvc.perform(delete("/v1/users/del/{id}", "id-inexistente")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(delete("/v1/users/del/{id}", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteUser_deveRetornar403_quandoTokenComRoleUser() throws Exception {
        mockMvc.perform(delete("/v1/users/del/{id}", USER_ID)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /users/remove/me ───────────────────────────────────────────────

    @Test
    void removeCurrentUser_deveRetornar204EPassarUserIdDoJwt() throws Exception {
        mockMvc.perform(delete("/v1/users/remove/me")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNoContent());

        verify(registerService).deactivateUser(USER_ID);
    }

    @Test
    void removeCurrentUser_deveRetornar404_quandoIdDoJwtNaoExiste() throws Exception {
        doThrow(new DomainEntityNotFound(User.class, "ID", USER_ID))
                .when(registerService).deactivateUser(USER_ID);

        mockMvc.perform(delete("/v1/users/remove/me")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeCurrentUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(delete("/v1/users/remove/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeCurrentUser_deveRetornar403_quandoTokenSemRoleUser() throws Exception {
        mockMvc.perform(delete("/v1/users/remove/me")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER"))))
                .andExpect(status().isForbidden());
    }

    // ── GET /users/me ─────────────────────────────────────────────────────────

    @Test
    void getCurrentUser_deveRetornar200ComBodyEPassarUserIdDoJwt() throws Exception {
        when(searchService.searchById(USER_ID)).thenReturn(buildResponse());

        mockMvc.perform(get("/v1/users/me")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID));

        verify(searchService).searchById(USER_ID);
    }

    @Test
    void getCurrentUser_deveRetornar404_quandoIdDoJwtNaoExiste() throws Exception {
        when(searchService.searchById(USER_ID))
                .thenThrow(new DomainEntityNotFound(User.class, "ID", USER_ID));

        mockMvc.perform(get("/v1/users/me")
                .with(jwt()
                        .jwt(b -> b.claim("userID", USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCurrentUser_deveRetornar401_quandoSemToken() throws Exception {
        mockMvc.perform(get("/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCurrentUser_deveRetornar403_quandoTokenSemRoleUser() throws Exception {
        mockMvc.perform(get("/v1/users/me")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OTHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchAll_deveRetornar403_quandoTokenComApenasRoleAdmin() throws Exception {
        mockMvc.perform(get("/v1/users")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void register_deveRetornarBodyComDadosDoUsuario_quandoCadastroValido() throws Exception {
        when(registerService.registerUser(any())).thenReturn(buildResponse());

        mockMvc.perform(post("/v1/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest("Fulano", "fulano@email.com", "senha123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(USER_ID))
                .andExpect(jsonPath("$.name").value("Fulano"))
                .andExpect(jsonPath("$.email").value("fulano@email.com"))
                .andExpect(jsonPath("$.active").value(true));
    }
}
