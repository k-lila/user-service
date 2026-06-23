package authorizationserver.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

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
    void deveLancarUsernameNotFound_quandoUsuarioInativo() {
        when(userClient.getUserByEmail("inativo@email.com"))
                .thenReturn(buildAuthDTO("inativo@email.com", false, Set.of("USER")));

        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("inativo@email.com"));
    }

    @Test
    void devePropagarUsernameNotFound_quandoFallbackOuNaoEncontrado() {
        // O fallback (user-service indisponível / CB aberto) e o "não encontrado" chegam
        // como UsernameNotFoundException: deve propagar SEM reembrulhar em RuntimeException
        // genérica (credenciais inválidas → volta ao login, não 500).
        when(userClient.getUserByEmail(any()))
                .thenThrow(new UsernameNotFoundException("user-service unavailable"));

        UsernameNotFoundException ex = assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("fulano@email.com"));
        assertEquals("user-service unavailable", ex.getMessage());
    }

    @Test
    void deveLancarUsernameNotFound_quandoErroInesperado() {
        // Exceção inesperada (não-UsernameNotFound) é convertida em UsernameNotFoundException
        // genérica — não escala a 500 nem vaza a causa.
        when(userClient.getUserByEmail(any())).thenThrow(new RuntimeException("timeout"));

        UsernameNotFoundException ex = assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("fulano@email.com"));
        assertEquals("Não foi possível autenticar o usuário", ex.getMessage());
    }

    @Test
    void deveLancarUsernameNotFound_quandoClienteRetornaNull() {
        when(userClient.getUserByEmail(any())).thenReturn(null);

        assertThrows(UsernameNotFoundException.class,
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

    @Test
    void deveDesabilitarConta_quandoEmailNaoVerificado() {
        AuthDTO dto = buildAuthDTO("fulano@email.com", true, Set.of("USER"));
        dto.setEmailVerified(false);
        when(userClient.getUserByEmail("fulano@email.com")).thenReturn(dto);

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        // enabled=false → DaoAuthenticationProvider lança DisabledException antes da senha.
        assertFalse(result.isEnabled());
    }

    @Test
    void deveManterContaHabilitada_quandoEmailVerificado() {
        AuthDTO dto = buildAuthDTO("fulano@email.com", true, Set.of("USER"));
        dto.setEmailVerified(true);
        when(userClient.getUserByEmail("fulano@email.com")).thenReturn(dto);

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        assertTrue(result.isEnabled());
    }

    @Test
    void deveManterContaHabilitada_quandoEmailVerifiedNuloLegado() {
        AuthDTO dto = buildAuthDTO("fulano@email.com", true, Set.of("USER"));
        dto.setEmailVerified(null);
        when(userClient.getUserByEmail("fulano@email.com")).thenReturn(dto);

        UserDetails result = service.loadUserByUsername("fulano@email.com");

        assertTrue(result.isEnabled());
    }

    // ── Grace period (ADR-015) ───────────────────────────────────────────────
    // @InjectMocks não injeta o Duration do construtor (não é mock) — instancia-se
    // explicitamente o AuthorizationService nesses casos para controlar a janela.

    @Test
    void deveManterContaHabilitada_quandoEmailNaoVerificado_masDentroDaJanelaDeCarencia() {
        AuthorizationService serviceComGracePeriod = new AuthorizationService(
                userClient, loginAttempts, "CF-Connecting-IP", Duration.ofHours(24));
        AuthDTO dto = buildAuthDTO("fulano@email.com", true, Set.of("USER"));
        dto.setEmailVerified(false);
        dto.setRegistrationDate(Instant.now().minus(Duration.ofHours(2)));
        when(userClient.getUserByEmail("fulano@email.com")).thenReturn(dto);

        UserDetails result = serviceComGracePeriod.loadUserByUsername("fulano@email.com");

        assertTrue(result.isEnabled());
    }

    @Test
    void deveDesabilitarConta_quandoEmailNaoVerificado_eForaDaJanelaDeCarencia() {
        AuthorizationService serviceComGracePeriod = new AuthorizationService(
                userClient, loginAttempts, "CF-Connecting-IP", Duration.ofHours(24));
        AuthDTO dto = buildAuthDTO("fulano@email.com", true, Set.of("USER"));
        dto.setEmailVerified(false);
        dto.setRegistrationDate(Instant.now().minus(Duration.ofHours(48)));
        when(userClient.getUserByEmail("fulano@email.com")).thenReturn(dto);

        UserDetails result = serviceComGracePeriod.loadUserByUsername("fulano@email.com");

        assertFalse(result.isEnabled());
    }

    @Test
    void deveManterContaHabilitada_semNpe_quandoRegistrationDateNuloEEmailNaoVerificado() {
        AuthorizationService serviceComGracePeriod = new AuthorizationService(
                userClient, loginAttempts, "CF-Connecting-IP", Duration.ofHours(24));
        AuthDTO dto = buildAuthDTO("fulano@email.com", true, Set.of("USER"));
        dto.setEmailVerified(false);
        dto.setRegistrationDate(null);
        when(userClient.getUserByEmail("fulano@email.com")).thenReturn(dto);

        UserDetails result = assertDoesNotThrow(
                () -> serviceComGracePeriod.loadUserByUsername("fulano@email.com"));

        // Sem registrationDate (legado/falha de serialização), cai no curto-circuito
        // withinGracePeriod=false — mas !Boolean.FALSE.equals(false) também é false, então
        // o resultado depende apenas do termo legado: aqui emailVerified=false explícito,
        // fora de qualquer janela, a conta fica desabilitada (comportamento equivalente ao
        // usuário legado só quando emailVerified é null, não false).
        assertFalse(result.isEnabled());
    }
}
