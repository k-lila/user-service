package com.users.userservice.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

    @Mock private IUserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    private RegisterService service;

    @BeforeEach
    void setUp() {
        service = new RegisterService(userRepository, passwordEncoder);
        ReflectionTestUtils.setField(service, "cacheManager", cacheManager);
        lenient().when(cacheManager.getCache("users")).thenReturn(cache);
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

    @Test
    void deveSalvarUsuario_quandoDadosValidos() {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName("Fulano");
        dto.setEmail("fulano@email.com");
        dto.setPasswordHash("senha123");

        User saved = buildUser("id-1", "fulano@email.com", true);

        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("senha123")).thenReturn("$2a$10$hashed");
        when(userRepository.insert(any(User.class))).thenReturn(saved);

        UserResponseDTO result = service.registerUser(dto);

        assertNotNull(result);
        verify(passwordEncoder).encode("senha123");
        verify(userRepository).insert(any(User.class));
    }

    @Test
    void deveLancarRuntimeException_quandoEmailJaCadastrado() {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setEmail("existente@email.com");

        when(userRepository.findByEmail("existente@email.com"))
                .thenReturn(Optional.of(buildUser("id-1", "existente@email.com", true)));

        assertThrows(RuntimeException.class, () -> service.registerUser(dto));
    }

    @Test
    void deveAtualizarUsuario_quandoIdExiste() {
        String userId = "id-1";
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName("Novo Nome");
        dto.setEmail("fulano@email.com");

        User existing = buildUser(userId, "fulano@email.com", true);
        User updated = buildUser(userId, "fulano@email.com", true);
        updated.setName("Novo Nome");

        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenReturn(updated);

        UserResponseDTO result = service.updateUser(dto, userId);

        assertNotNull(result);
        verify(userRepository).save(any(User.class));
        verify(cache, times(2)).evict("fulano@email.com");
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoIdNaoExisteNoUpdate() {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setEmail("fulano@email.com");

        when(userRepository.existsById("id-inexistente")).thenReturn(false);

        assertThrows(DomainEntityNotFound.class, () -> service.updateUser(dto, "id-inexistente"));
    }

    @Test
    void deveLancarRuntimeException_quandoEmailDiferenteNoUpdate() {
        // ATENÇÃO: testa o comportamento ATUAL (BUGADO). Ver CLAUDE.md > Bugs Conhecidos.
        String userId = "id-1";
        UserRequestDTO dto = new UserRequestDTO();
        dto.setEmail("novo@email.com");

        User existing = buildUser(userId, "antigo@email.com", true);

        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(existing));

        assertThrows(RuntimeException.class, () -> service.updateUser(dto, userId));
    }

    @Test
    void deveDeletarUsuario_quandoIdExiste() {
        User user = buildUser("id-1", "fulano@email.com", true);

        when(userRepository.findById("id-1")).thenReturn(Optional.of(user));

        service.deleteUser("id-1");

        verify(userRepository).delete(user);
        verify(cache).evict("fulano@email.com");
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoIdNaoExisteNoDelete() {
        when(userRepository.findById("id-inexistente")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class, () -> service.deleteUser("id-inexistente"));
    }

    @Test
    void deveDesativarUsuario_quandoIdExiste() {
        User user = buildUser("id-1", "fulano@email.com", true);

        when(userRepository.findById("id-1")).thenReturn(Optional.of(user));

        service.deactivateUser("id-1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertFalse(captor.getValue().getActive());
        verify(cache).evict("fulano@email.com");
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoIdNaoExisteNaDesativacao() {
        when(userRepository.findById("id-inexistente")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class, () -> service.deactivateUser("id-inexistente"));
    }
}
