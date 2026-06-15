package com.users.userservice.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.repository.IUserRepository;
import com.users.userservice.dtos.AuthDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.services.AuthenticationService;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

class CacheIntegrationTest extends AbstractIntegrationTest {

    private static final String NOME     = "Fulano";
    private static final String EMAIL    = "fulano@email.com";
    private static final String SENHA    = "senha123";
    private static final String NOVO_NOME  = "Novo Nome";
    private static final String NOVO_EMAIL = "novo@email.com";

    @Autowired RegisterService registerService;
    @Autowired SearchService searchService;
    @Autowired AuthenticationService authenticationService;
    @Autowired CacheManager cacheManager;
    @Autowired IUserRepository userRepository;

    private Cache cacheById;
    private Cache cacheByEmail;

    @BeforeEach
    void limpar() {
        userRepository.deleteAll();
        cacheById = cacheManager.getCache("usersById");
        cacheByEmail = cacheManager.getCache("usersByEmail");
    }

    @AfterEach
    void limparApos() {
        userRepository.deleteAll();
        cacheById.clear();
        cacheByEmail.clear();
    }

    private UserRequestDTO buildDTO(String nome, String email, String senha) {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName(nome);
        dto.setEmail(email);
        dto.setPassword(senha);
        return dto;
    }

    @Test
    void devePopularCache_aposConsultaPorId() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO(NOME, EMAIL, SENHA));
        String id = registrado.getId();
        assertNotNull(id);
        assertNull(cacheById.get(id));
        searchService.searchById(id);
        searchService.searchById(id);
        assertNotNull(cacheById.get(id));
        assertEquals(EMAIL,
                ((UserResponseDTO) cacheById.get(id).get()).getEmail());
    }

    @Test
    void devePopularCache_aposConsultaPorEmail() {
        registerService.registerUser(buildDTO(NOME, EMAIL, SENHA));
        assertNull(cacheByEmail.get(EMAIL));
        searchService.searchByEmail(EMAIL);
        searchService.searchByEmail(EMAIL);
        assertNotNull(cacheByEmail.get(EMAIL));
        assertEquals(NOME,
                ((UserResponseDTO) cacheByEmail.get(EMAIL).get()).getName());
    }

    @Test
    void deveEvictarCachePorId_aposDelete() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO(NOME, EMAIL, SENHA));
        String id = registrado.getId();
        searchService.searchById(id);
        searchService.searchById(id);
        registerService.deleteUser(id);
        assertNull(cacheById.get(id));
    }

    @Test
    void deveEvictarCachePorEmail_aposDelete() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO(NOME, EMAIL, SENHA));
        searchService.searchByEmail(EMAIL);
        searchService.searchByEmail(EMAIL);
        registerService.deleteUser(registrado.getId());
        assertNull(cacheByEmail.get(EMAIL));
    }

    @Test
    void deveAtualizarCachePorId_comUsuarioDesativado_aposDesativacao() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO(NOME, EMAIL, SENHA));
        String id = registrado.getId();
        searchService.searchById(id);
        searchService.searchById(id);
        assertNotNull(cacheById.get(id));
        registerService.deactivateUser(id);
        Cache.ValueWrapper wrapper = cacheById.get(id);
        assertNull(wrapper);
    }

    @Test
    void deveEvictarCachePorEmail_aposDesativacao() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO(NOME, EMAIL, SENHA));

        searchService.searchByEmail(EMAIL);
        searchService.searchByEmail(EMAIL);

        registerService.deactivateUser(registrado.getId());

        assertNull(cacheByEmail.get(EMAIL));
    }

    @Test
    void devePopularCachePorId_aposUpdateUser() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO(NOME, EMAIL, SENHA));
        String id = registrado.getId();

        searchService.searchById(id);
        searchService.searchById(id);

        registerService.updateUser(buildDTO(NOVO_NOME, EMAIL, null), id);

        // O put no Redis tem visibilidade eventual (alguns ms) neste stack;
        // aguarda o cache refletir a atualização antes de asserir.
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            Cache.ValueWrapper wrapper = cacheById.get(id);
            assertNotNull(wrapper);
            assertEquals(NOVO_NOME, ((UserResponseDTO) wrapper.get()).getName());
        });
    }

    @Test
    void deveAtualizarCachePorEmail_comDadosNovos_aposUpdateUser() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO(NOME, EMAIL, SENHA));
        String id = registrado.getId();
        searchService.searchByEmail(EMAIL);
        searchService.searchByEmail(EMAIL);
        registerService.updateUser(buildDTO(NOVO_NOME, EMAIL, null), id);
        searchService.searchByEmail(EMAIL);
        searchService.searchByEmail(EMAIL);
        Cache.ValueWrapper wrapper = cacheByEmail.get(EMAIL);
        assertNotNull(wrapper);
        assertEquals(NOVO_NOME, ((UserResponseDTO) wrapper.get()).getName());
    }

    // ── authByEmail ──────────────────────────────────────────────────────────

    @Test
    void devePopularAuthCache_aposGetUserByEmail() {
        registerService.registerUser(buildDTO(NOME, EMAIL, SENHA));
        Cache authCache = cacheManager.getCache("authByEmail");

        assertNull(authCache.get(EMAIL));

        authenticationService.getUserByEmail(EMAIL);
        authenticationService.getUserByEmail(EMAIL);

        assertNotNull(authCache.get(EMAIL));
    }

    @Test
    void devePreservarCamposDoAuthDTO_aposRoundTripRedis() {
        registerService.registerUser(buildDTO(NOME, EMAIL, SENHA));

        authenticationService.getUserByEmail(EMAIL);
        authenticationService.getUserByEmail(EMAIL);

        Cache authCache = cacheManager.getCache("authByEmail");
        AuthDTO cached = (AuthDTO) authCache.get(EMAIL).get();

        assertNotNull(cached.getId());
        assertEquals(EMAIL, cached.getEmail());
        assertNotNull(cached.getPasswordHash());
        assertTrue(cached.getActive());
        assertTrue(cached.getRoles().contains("USER"));
    }

    @Test
    void deveEvictarAuthCache_aposDeactivateUser_eBloquearLogin() {
        UserResponseDTO registrado = registerService.registerUser(buildDTO(NOME, EMAIL, SENHA));

        authenticationService.getUserByEmail(EMAIL);
        authenticationService.getUserByEmail(EMAIL);

        registerService.deactivateUser(registrado.getId());

        Cache authCache = cacheManager.getCache("authByEmail");
        // Evict no Redis tem visibilidade eventual (alguns ms); aguarda refletir.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertNull(authCache.get(EMAIL)));
        assertThrows(DomainEntityNotFound.class, () -> authenticationService.getUserByEmail(EMAIL));
    }

    @Test
    void deveEvictarAuthCache_aposDeleteUser_eBloquearLogin() {
        UserResponseDTO registrado = registerService.registerUser(buildDTO(NOME, EMAIL, SENHA));

        authenticationService.getUserByEmail(EMAIL);
        authenticationService.getUserByEmail(EMAIL);

        registerService.deleteUser(registrado.getId());

        Cache authCache = cacheManager.getCache("authByEmail");
        // Evict no Redis tem visibilidade eventual (alguns ms); aguarda refletir.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertNull(authCache.get(EMAIL)));
        assertThrows(DomainEntityNotFound.class, () -> authenticationService.getUserByEmail(EMAIL));
    }

    @Test
    void deveEvictarAuthCacheDoEmailAntigo_aposUpdateUser() {
        UserResponseDTO registrado = registerService.registerUser(buildDTO(NOME, EMAIL, SENHA));

        authenticationService.getUserByEmail(EMAIL);
        authenticationService.getUserByEmail(EMAIL);

        registerService.updateUser(buildDTO(NOVO_NOME, NOVO_EMAIL, null), registrado.getId());

        Cache authCache = cacheManager.getCache("authByEmail");
        // Evict no Redis tem visibilidade eventual (alguns ms); aguarda refletir.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertNull(authCache.get(EMAIL)));
    }

    @Test
    void deveNaoCachearExcecao_quandoEmailNaoExiste() {
        Cache authCache = cacheManager.getCache("authByEmail");

        assertThrows(DomainEntityNotFound.class, () -> authenticationService.getUserByEmail(EMAIL));
        assertNull(authCache.get(EMAIL));

        registerService.registerUser(buildDTO(NOME, EMAIL, SENHA));
        AuthDTO resultado = authenticationService.getUserByEmail(EMAIL);
        assertNotNull(resultado);
    }
}
