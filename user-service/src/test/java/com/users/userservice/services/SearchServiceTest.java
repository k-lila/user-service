package com.users.userservice.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock private IUserRepository userRepository;
    @Mock private CacheManager cacheManager;
    @InjectMocks private SearchService service;

    private User buildUser(String id, String email) {
        return buildUser(id, email, true);
    }

    private User buildUser(String id, String email, boolean active) {
        User u = new User();
        u.setId(id);
        u.setName("Fulano");
        u.setEmail(email);
        u.setPasswordHash("$2a$10$hashed");
        u.setRegistrationDate(Instant.now());
        u.setRoles(Set.of("USER"));
        u.setActive(active);
        return u;
    }

    // Os testes de searchAll saíram junto do método (ADR-021). A paginação sobrevive só na
    // superfície ADMIN — coberta por AdminServiceTest/AdminFlowIntegrationTest.

    @Test
    void deveRetornarUsuario_quandoIdValido() {
        User user = buildUser("id-1", "fulano@email.com");

        when(userRepository.findById("id-1")).thenReturn(Optional.of(user));

        UserResponseDTO result = service.searchById("id-1");

        assertNotNull(result);
        assertEquals("id-1", result.getId());
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoIdInvalido() {
        when(userRepository.findById("id-invalido")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class, () -> service.searchById("id-invalido"));
    }

    // ADR-001: usuário inativo (soft-deleted) é tratado como inexistente nas leituras.
    @Test
    void deveLancarDomainEntityNotFound_quandoUsuarioInativoPorId() {
        when(userRepository.findById("id-inativo"))
                .thenReturn(Optional.of(buildUser("id-inativo", "inativo@email.com", false)));

        assertThrows(DomainEntityNotFound.class, () -> service.searchById("id-inativo"));
    }

    // ADR-001: usuário inativo (soft-deleted) é tratado como inexistente nas leituras.
    @Test
    void deveLancarDomainEntityNotFound_quandoUsuarioInativoPorEmail() {
        when(userRepository.findByEmail("inativo@email.com"))
                .thenReturn(Optional.of(buildUser("id-inativo", "inativo@email.com", false)));

        assertThrows(DomainEntityNotFound.class, () -> service.searchByEmail("inativo@email.com"));
    }

    @Test
    void deveRetornarUsuario_quandoEmailValido() {
        User user = buildUser("id-1", "fulano@email.com");

        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.of(user));

        UserResponseDTO result = service.searchByEmail("fulano@email.com");

        assertNotNull(result);
        assertEquals("fulano@email.com", result.getEmail());
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoEmailNaoExiste() {
        when(userRepository.findByEmail("nao@existe.com")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class, () -> service.searchByEmail("nao@existe.com"));
    }

    @Test
    void deveMapearTodosCamposDoDTO_quandoBuscaPorId() {
        User user = buildUser("id-1", "fulano@email.com");

        when(userRepository.findById("id-1")).thenReturn(Optional.of(user));

        UserResponseDTO result = service.searchById("id-1");

        assertEquals("id-1", result.getId());
        assertEquals("Fulano", result.getName());
        assertEquals("fulano@email.com", result.getEmail());
        assertTrue(result.getActive());
    }

}
