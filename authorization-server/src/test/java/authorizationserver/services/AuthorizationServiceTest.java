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
    // isBlocked() retorna false por padrão (mock) → accountNonLocked=true; não afeta os casos abaixo.
    @Mock private LoginAttemptService loginAttempts;
    @InjectMocks private AuthorizationService service;

    private AuthDTO buildAuthDTO(String email, boolean active, Set<String> roles) {
        AuthDTO dto = new AuthDTO();
        dto.setId("test-id");
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

        assertThrows(RuntimeException.class,
                () -> service.loadUserByUsername("fulano@email.com"));
    }

    @Test
    void deveLancarRuntimeException_quandoClienteRetornaNull() {
        when(userClient.getUserByEmail(any())).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> service.loadUserByUsername("fulano@email.com"));
    }

    @Test
    void deveTerApenasUserIdAuthority_quandoRolesVazio() {
        when(userClient.getUserByEmail("fulano@email.com"))
                .thenReturn(buildAuthDTO("fulano@email.com", true, Set.of()));

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        assertNotNull(result);
        // Sem roles → sem ROLE_* authorities; apenas USER_ID: é adicionado
        assertTrue(result.getAuthorities().stream()
            .noneMatch(a -> a.getAuthority().startsWith("ROLE_")));
        assertTrue(result.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().startsWith("USER_ID:")));
    }

    @Test
    void deveMarcarContaBloqueada_quandoLoginAttemptsBloqueado() {
        when(userClient.getUserByEmail("fulano@email.com"))
                .thenReturn(buildAuthDTO("fulano@email.com", true, Set.of("USER")));
        when(loginAttempts.isBlocked(eq("fulano@email.com"), anyString())).thenReturn(true);

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        // accountNonLocked=false → DaoAuthenticationProvider lança LockedException antes da senha.
        assertFalse(result.isAccountNonLocked());
    }

    @Test
    void deveManterContaDesbloqueada_quandoLoginAttemptsLivre() {
        when(userClient.getUserByEmail("fulano@email.com"))
                .thenReturn(buildAuthDTO("fulano@email.com", true, Set.of("USER")));
        when(loginAttempts.isBlocked(eq("fulano@email.com"), anyString())).thenReturn(false);

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        assertTrue(result.isAccountNonLocked());
    }
}
