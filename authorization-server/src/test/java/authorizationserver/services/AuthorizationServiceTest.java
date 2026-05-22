package authorizationserver.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import authorizationserver.clients.IUserClient;
import authorizationserver.dtos.AuthDTO;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock private IUserClient userClient;
    @InjectMocks private AuthorizationService service;

    private AuthDTO buildAuthDTO(String email, boolean active, Set<String> roles) {
        AuthDTO dto = new AuthDTO();
        dto.setEmail(email);
        dto.setPasswordHash("$2a$10$hashed");
        dto.setActive(active);
        dto.setRoles(roles);
        return dto;
    }

    @Test
    void deveRetornarUserDetails_quandoUsuarioAtivoExiste() {
        when(userClient.getUserByEmail("fulano@email.com"))
                .thenReturn(buildAuthDTO("fulano@email.com", true, Set.of("USER")));

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        assertNotNull(result);
        assertEquals("fulano@email.com", result.getUsername());
        assertTrue(result.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void deveMapearMultiplasRoles_corretamente() {
        when(userClient.getUserByEmail("fulano@email.com"))
                .thenReturn(buildAuthDTO("fulano@email.com", true, Set.of("USER", "ADMIN")));

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        assertTrue(result.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
        assertTrue(result.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void deveLancarRuntimeException_quandoUsuarioInativo() {
        // UsernameNotFoundException lançada internamente é capturada e relançada como RuntimeException
        when(userClient.getUserByEmail("inativo@email.com"))
                .thenReturn(buildAuthDTO("inativo@email.com", false, Set.of("USER")));

        assertThrows(RuntimeException.class, () -> service.loadUserByUsername("inativo@email.com"));
    }

    @Test
    void deveLancarRuntimeException_quandoClienteLancaExcecao() {
        when(userClient.getUserByEmail(any())).thenThrow(new RuntimeException("timeout"));

        RuntimeException excecao = assertThrows(RuntimeException.class,
                () -> service.loadUserByUsername("fulano@email.com"));

        assertTrue(excecao.getMessage().contains("Erro de comunicação"));
    }
}
