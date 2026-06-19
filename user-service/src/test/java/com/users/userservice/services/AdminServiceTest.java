package com.users.userservice.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.users.userservice.domain.AuditAction;
import com.users.userservice.domain.AuditLog;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.AdminUserResponseDTO;
import com.users.userservice.dtos.AuditLogResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.SelfRoleRevocationException;
import com.users.userservice.repository.IAuditLogRepository;
import com.users.userservice.repository.IUserRepository;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private IUserRepository userRepository;
    @Mock private IAuditLogRepository auditLogRepository;
    @Mock private CacheService cacheService;
    @Mock private MongoTemplate mongoTemplate;

    private AdminService service;

    private static final String ADMIN_ID = "admin-id-1";
    private static final String OTHER_ID = "other-id-1";

    @BeforeEach
    void setUp() {
        service = new AdminService(userRepository, auditLogRepository, cacheService, mongoTemplate);
    }

    private User buildUser(String id, String email, boolean active, Set<String> roles) {
        User u = new User();
        u.setId(id);
        u.setName("Fulano");
        u.setEmail(email);
        u.setPasswordHash("$2a$10$hashed");
        u.setRegistrationDate(Instant.now());
        u.setRoles(new HashSet<>(roles));
        u.setActive(active);
        return u;
    }

    // ── listAllUsers ─────────────────────────────────────────────────────────

    @Test
    void listAllUsers_deveRetornarPaginaComUsuariosAtivosEInativos() {
        Pageable pageable = PageRequest.of(0, 10);
        User active = buildUser("id-1", "ativo@email.com", true, Set.of("USER"));
        User inactive = buildUser("id-2", "inativo@email.com", false, Set.of("USER"));

        when(mongoTemplate.count(any(Query.class), eq(User.class))).thenReturn(2L);
        when(mongoTemplate.find(any(Query.class), eq(User.class))).thenReturn(List.of(active, inactive));

        Page<AdminUserResponseDTO> result = service.listAllUsers(null, null, null, pageable);

        assertEquals(2, result.getTotalElements());
        assertTrue(result.getContent().stream().anyMatch(dto -> !dto.getActive()));
    }

    @Test
    void listAllUsers_deveIncluirRolesNoDTO() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = buildUser("id-1", "fulano@email.com", true, Set.of("USER", "ADMIN"));

        when(mongoTemplate.count(any(Query.class), eq(User.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(User.class))).thenReturn(List.of(user));

        Page<AdminUserResponseDTO> result = service.listAllUsers(null, null, null, pageable);

        assertEquals(Set.of("USER", "ADMIN"), result.getContent().get(0).getRoles());
    }

    // ── getAuditLogsByUser ───────────────────────────────────────────────────

    @Test
    void getAuditLogsByUser_deveRetornarLogsDoTitular_quandoTitularExiste() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(buildUser(OTHER_ID, "x@email.com", true, Set.of("USER"))));
        AuditLog log = new AuditLog();
        log.setAction(AuditAction.UPDATE);
        log.setTargetUserId(OTHER_ID);
        when(auditLogRepository.findByTargetUserId(eq(OTHER_ID), any())).thenReturn(new PageImpl<>(List.of(log)));

        Page<AuditLogResponseDTO> result = service.getAuditLogsByUser(OTHER_ID, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(AuditAction.UPDATE, result.getContent().get(0).getAction());
    }

    @Test
    void getAuditLogsByUser_deveLancar404_quandoTitularNaoExiste() {
        when(userRepository.findById("inexistente")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class,
                () -> service.getAuditLogsByUser("inexistente", PageRequest.of(0, 20)));

        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void getAuditLogsByUser_deveClampSizeNoTeto_quandoSizeExcedeTeto() {
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(buildUser(OTHER_ID, "x@email.com", true, Set.of("USER"))));
        when(auditLogRepository.findByTargetUserId(eq(OTHER_ID), any())).thenReturn(new PageImpl<>(List.of()));

        service.getAuditLogsByUser(OTHER_ID, PageRequest.of(0, 100000));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findByTargetUserId(eq(OTHER_ID), captor.capture());
        assertEquals(AdminService.MAX_AUDIT_PAGE_SIZE, captor.getValue().getPageSize());
    }

    // ── getAllAuditLogs ──────────────────────────────────────────────────────

    @Test
    void getAllAuditLogs_deveRetornarFeedGeralPaginado() {
        AuditLog log = new AuditLog();
        log.setAction(AuditAction.REGISTER);
        when(auditLogRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(log)));

        Page<AuditLogResponseDTO> result = service.getAllAuditLogs(PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void getAllAuditLogs_deveClampSizeNoTeto_quandoSizeExcedeTeto() {
        when(auditLogRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.getAllAuditLogs(PageRequest.of(0, 100000));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findAll(captor.capture());
        assertEquals(AdminService.MAX_AUDIT_PAGE_SIZE, captor.getValue().getPageSize());
    }

    // ── updateUserRoles ──────────────────────────────────────────────────────

    @Test
    void updateUserRoles_devePromoverUsuarioAAdmin_eRetornarAdminGranted() {
        User user = buildUser(OTHER_ID, "outro@email.com", true, Set.of("USER"));
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminService.RoleUpdateResult result = service.updateUserRoles(OTHER_ID, Set.of("USER", "ADMIN"), ADMIN_ID);

        assertTrue(result.adminGranted());
        assertFalse(result.adminRevoked());
        assertEquals(Set.of("USER", "ADMIN"), result.response().getRoles());
        verify(cacheService).evictById(OTHER_ID);
        verify(cacheService).evictByEmail("outro@email.com");
        verify(cacheService).evictByEmailAuth("outro@email.com");
    }

    @Test
    void updateUserRoles_deveRevogarAdminDeTerceiro_eRetornarAdminRevoked() {
        User user = buildUser(OTHER_ID, "outro@email.com", true, Set.of("USER", "ADMIN"));
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminService.RoleUpdateResult result = service.updateUserRoles(OTHER_ID, Set.of("USER"), ADMIN_ID);

        assertFalse(result.adminGranted());
        assertTrue(result.adminRevoked());
        assertEquals(Set.of("USER"), result.response().getRoles());
    }

    @Test
    void updateUserRoles_naoDeveSinalizarTransicao_quandoRolesNaoMudamEmAdmin() {
        User user = buildUser(OTHER_ID, "outro@email.com", true, Set.of("USER"));
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminService.RoleUpdateResult result = service.updateUserRoles(OTHER_ID, Set.of("USER"), ADMIN_ID);

        assertFalse(result.adminGranted());
        assertFalse(result.adminRevoked());
    }

    @Test
    void updateUserRoles_deveBloquearAutoRevogacao_quandoAtorEhOProprioAlvoEPossuiAdminPersistido() {
        User user = buildUser(ADMIN_ID, "admin@email.com", true, Set.of("USER", "ADMIN"));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(user));

        assertThrows(SelfRoleRevocationException.class,
                () -> service.updateUserRoles(ADMIN_ID, Set.of("USER"), ADMIN_ID));

        verify(userRepository, never()).save(any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void updateUserRoles_devePermitirAutoAtualizacao_quandoNaoRemoveAdmin() {
        User user = buildUser(ADMIN_ID, "admin@email.com", true, Set.of("USER", "ADMIN"));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminService.RoleUpdateResult result = service.updateUserRoles(ADMIN_ID, Set.of("USER", "ADMIN"), ADMIN_ID);

        assertNotNull(result);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void updateUserRoles_devePriorizar400SobreAutoRevogacao_quandoPayloadInvalidoEAutoRevogacaoCoincidem() {
        User user = buildUser(ADMIN_ID, "admin@email.com", true, Set.of("USER", "ADMIN"));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateUserRoles(ADMIN_ID, Set.of("ADMIN"), ADMIN_ID));

        verify(userRepository, never()).save(any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void updateUserRoles_devePriorizar404SobrePayloadInvalido_quandoAlvoInexistenteERolesInvalidas() {
        when(userRepository.findById("inexistente")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class,
                () -> service.updateUserRoles("inexistente", Set.of("SUPERADMIN"), ADMIN_ID));

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserRoles_deveRejeitarComBadRequest_quandoPayloadSemUser() {
        User user = buildUser(OTHER_ID, "outro@email.com", true, Set.of("USER", "ADMIN"));
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateUserRoles(OTHER_ID, Set.of("ADMIN"), ADMIN_ID));

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserRoles_deveRejeitarComBadRequest_quandoRoleForaDoConjuntoPermitido() {
        User user = buildUser(OTHER_ID, "outro@email.com", true, Set.of("USER"));
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateUserRoles(OTHER_ID, Set.of("USER", "SUPERADMIN"), ADMIN_ID));

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUserRoles_deveLancar404_quandoUsuarioAlvoNaoExiste() {
        when(userRepository.findById("inexistente")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class,
                () -> service.updateUserRoles("inexistente", Set.of("USER"), ADMIN_ID));

        verifyNoInteractions(cacheService);
    }
}
