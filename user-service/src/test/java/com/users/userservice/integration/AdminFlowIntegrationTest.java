package com.users.userservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.users.userservice.dtos.AdminUserResponseDTO;
import com.users.userservice.dtos.AuthDTO;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.SelfRoleRevocationException;
import com.users.userservice.repository.IUserRepository;
import com.users.userservice.services.AdminService;
import com.users.userservice.services.AdminService.RoleUpdateResult;
import com.users.userservice.services.AuthenticationService;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

/**
 * Integração da superfície administrativa (ADR-014): listagem incluindo inativos persistidos
 * de fato, gestão de roles com evicção real dos 3 caches Redis e bloqueio de auto-revogação
 * contra o estado persistido no MongoDB real (Testcontainers).
 */
class AdminFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired AdminService adminService;
    @Autowired RegisterService registerService;
    @Autowired SearchService searchService;
    @Autowired AuthenticationService authenticationService;
    @Autowired CacheManager cacheManager;
    @Autowired IUserRepository userRepository;

    @BeforeEach
    void limpar() {
        userRepository.deleteAll();
    }

    @AfterEach
    void limparApos() {
        userRepository.deleteAll();
    }

    private UserRequestDTO buildDTO(String nome, String email, String senha) {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName(nome);
        dto.setEmail(email);
        dto.setPassword(senha);
        dto.setTermsAccepted(true);
        return dto;
    }

    @Test
    void deveIncluirUsuarioInativoPersistido_naListagemAdministrativa() {
        UserResponseDTO ativo = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        UserResponseDTO inativo = registerService.registerUser(
                buildDTO("Ciclano", "ciclano@email.com", "senha456"));
        registerService.deactivateUser(inativo.getId());

        Page<AdminUserResponseDTO> pagina = adminService.listAllUsers(null, null, null, PageRequest.of(0, 10));

        assertThat(pagina.getTotalElements()).isEqualTo(2);
        assertThat(pagina.getContent())
                .extracting(AdminUserResponseDTO::getActive)
                .containsExactlyInAnyOrder(true, false);
        assertThat(pagina.getContent())
                .filteredOn(dto -> dto.getId().equals(inativo.getId()))
                .singleElement()
                .satisfies(dto -> assertThat(dto.getActive()).isFalse());

        // ADR-001: a listagem pública continua excluindo o inativo, isolada da rota admin.
        Page<UserResponseDTO> paginaPublica = searchService.searchAll(PageRequest.of(0, 10));
        assertThat(paginaPublica.getTotalElements()).isEqualTo(1);
    }

    @Test
    void deveFiltrarPorActiveENome_naListagemAdministrativa() {
        UserResponseDTO maria = registerService.registerUser(
                buildDTO("Maria Silva", "maria@email.com", "senha123"));
        registerService.deactivateUser(maria.getId());
        registerService.registerUser(buildDTO("Maria Souza", "maria2@email.com", "senha456"));

        Page<AdminUserResponseDTO> pagina = adminService.listAllUsers(false, "Maria", null, PageRequest.of(0, 10));

        assertThat(pagina.getTotalElements()).isEqualTo(1);
        assertThat(pagina.getContent().get(0).getEmail()).isEqualTo("maria@email.com");
    }

    @Test
    void devePromoverARoles_eEvictarOsTresCachesReais_efetivamente() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        String id = registrado.getId();
        String email = registrado.getEmail();

        searchService.searchById(id);
        searchService.searchById(id);
        searchService.searchByEmail(email);
        searchService.searchByEmail(email);
        authenticationService.getUserByEmail(email);
        authenticationService.getUserByEmail(email);

        Cache cacheById = cacheManager.getCache("usersById");
        Cache cacheByEmail = cacheManager.getCache("usersByEmail");
        Cache authCache = cacheManager.getCache("authByEmail");
        assertThat(cacheById.get(id)).isNotNull();
        assertThat(cacheByEmail.get(email)).isNotNull();
        assertThat(authCache.get(email)).isNotNull();

        RoleUpdateResult result = adminService.updateUserRoles(id, Set.of("USER", "ADMIN"), "outro-admin-id");

        assertThat(result.adminGranted()).isTrue();
        assertThat(result.adminRevoked()).isFalse();
        assertThat(result.response().getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(cacheById.get(id)).isNull();
            assertThat(cacheByEmail.get(email)).isNull();
            assertThat(authCache.get(email)).isNull();
        });

        AuthDTO refeito = authenticationService.getUserByEmail(email);
        assertThat(refeito.getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void deveRevogarAdminDeTerceiro_eEvictarCachesReais() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        String id = registrado.getId();
        String email = registrado.getEmail();
        adminService.updateUserRoles(id, Set.of("USER", "ADMIN"), "outro-admin-id");

        authenticationService.getUserByEmail(email);
        authenticationService.getUserByEmail(email);
        Cache authCache = cacheManager.getCache("authByEmail");
        assertThat(authCache.get(email)).isNotNull();

        RoleUpdateResult result = adminService.updateUserRoles(id, Set.of("USER"), "outro-admin-id");

        assertThat(result.adminRevoked()).isTrue();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(authCache.get(email)).isNull());

        AuthDTO refeito = authenticationService.getUserByEmail(email);
        assertThat(refeito.getRoles()).containsExactly("USER");
    }

    @Test
    void deveBloquearAutoRevogacao_contraOEstadoPersistidoNoMongoReal() {
        UserResponseDTO admin = registerService.registerUser(
                buildDTO("Admin", "admin@email.com", "senha123"));
        adminService.updateUserRoles(admin.getId(), Set.of("USER", "ADMIN"), "outro-admin-bootstrap");

        assertThrows(SelfRoleRevocationException.class,
                () -> adminService.updateUserRoles(admin.getId(), Set.of("USER"), admin.getId()));

        var persistido = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(persistido.getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void deveLancar404_quandoAlvoInexistenteNaGestaoDeRoles() {
        assertThrows(DomainEntityNotFound.class,
                () -> adminService.updateUserRoles("id-inexistente", Set.of("USER"), "admin-id"));
    }
}
