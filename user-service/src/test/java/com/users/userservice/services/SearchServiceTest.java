package com.users.userservice.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock private IUserRepository userRepository;
    @InjectMocks private SearchService service;

    private User buildUser(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setName("Fulano");
        u.setEmail(email);
        u.setPasswordHash("$2a$10$hashed");
        u.setRegistrationDate(Instant.now());
        u.setRoles(Set.of("USER"));
        u.setActive(true);
        return u;
    }

    @Test
    void deveRetornarPaginaDeUsuarios_quandoExistemRegistros() {
        User user = buildUser("id-1", "fulano@email.com");

        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));
        when(userRepository.count()).thenReturn(1L);

        Page<UserResponseDTO> result = service.searchAll(PageRequest.of(0, 10));

        assertFalse(result.isEmpty());
    }

    @Test
    void deveRetornarPaginaVazia_quandoNaoExistemRegistros() {
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(userRepository.count()).thenReturn(0L);

        Page<UserResponseDTO> result = service.searchAll(PageRequest.of(0, 10));

        assertTrue(result.isEmpty());
    }

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

    @Test
    void deveMapearCamposDosUsuariosNaPagina_quandoBuscaTodos() {
        User user = buildUser("id-1", "fulano@email.com");

        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));
        when(userRepository.count()).thenReturn(1L);

        Page<UserResponseDTO> result = service.searchAll(PageRequest.of(0, 10));

        UserResponseDTO dto = result.getContent().get(0);
        assertEquals("id-1", dto.getId());
        assertEquals("Fulano", dto.getName());
        assertEquals("fulano@email.com", dto.getEmail());
        assertTrue(dto.getActive());
    }
}
