package com.users.userservice.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.AuthDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock private IUserRepository userRepository;
    @InjectMocks private AuthenticationService service;

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

    @Test
    void deveRetornarAuthDTO_quandoUsuarioAtivoExiste() {
        User user = buildUser("id-1", "fulano@email.com", true);

        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.of(user));

        AuthDTO result = service.getUserByEmail("fulano@email.com");

        assertNotNull(result);
        assertEquals("id-1", result.getId());
        assertEquals("fulano@email.com", result.getEmail());
        assertEquals("$2a$10$hashed", result.getPasswordHash());
        assertEquals(Set.of("USER"), result.getRoles());
        assertTrue(result.getActive());
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoEmailNaoExiste() {
        when(userRepository.findByEmail("nao@existe.com")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class, () -> service.getUserByEmail("nao@existe.com"));
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoUsuarioInativo() {
        User inactive = buildUser("id-1", "inativo@email.com", false);

        when(userRepository.findByEmail("inativo@email.com")).thenReturn(Optional.of(inactive));

        assertThrows(DomainEntityNotFound.class, () -> service.getUserByEmail("inativo@email.com"));
    }
}
