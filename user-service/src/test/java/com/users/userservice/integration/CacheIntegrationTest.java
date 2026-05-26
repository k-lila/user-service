package com.users.userservice.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.repository.IUserRepository;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

class CacheIntegrationTest extends AbstractIntegrationTest {

    @Autowired RegisterService registerService;
    @Autowired SearchService searchService;
    @Autowired CacheManager cacheManager;
    @Autowired IUserRepository userRepository;

    private Cache cacheById;
    private Cache cacheByEmail;

    @BeforeEach
    void limpar() {
        userRepository.deleteAll();
        cacheById = cacheManager.getCache("usersById");
        cacheByEmail = cacheManager.getCache("usersByEmail");
        if (cacheById != null) cacheById.clear();
        if (cacheByEmail != null) cacheByEmail.clear();
    }

    @AfterEach
    void limparApos() {
        userRepository.deleteAll();
        if (cacheById != null) cacheById.clear();
        if (cacheByEmail != null) cacheByEmail.clear();
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
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        String id = registrado.getId();
        assertNull(cacheById.get(id));
        searchService.searchById(id);
        searchService.searchById(id);
        assertNotNull(cacheById.get(id));
        assertEquals("fulano@email.com",
                ((UserResponseDTO) cacheById.get(id).get()).getEmail());
    }

    @Test
    void devePopularCache_aposConsultaPorEmail() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));

        assertNull(cacheByEmail.get("fulano@email.com"));

        searchService.searchByEmail("fulano@email.com");
        searchService.searchByEmail("fulano@email.com");

        assertNotNull(cacheByEmail.get("fulano@email.com"));
        assertEquals("Fulano",
                ((UserResponseDTO) cacheByEmail.get("fulano@email.com").get()).getName());
    }

    @Test
    void deveEvictarCachePorId_aposDelete() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        String id = registrado.getId();

        searchService.searchById(id);

        registerService.deleteUser(id);

        assertNull(cacheById.get(id));
    }

    @Test
    void deveEvictarCachePorEmail_aposDelete() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        searchService.searchByEmail("fulano@email.com");

        registerService.deleteUser(registrado.getId());

        assertNull(cacheByEmail.get("fulano@email.com"));
    }

    @Test
    void deveEvictarCachePorId_aposDesativacao() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        String id = registrado.getId();

        searchService.searchById(id);

        registerService.deactivateUser(id);

        assertNull(cacheById.get(id));
    }

    @Test
    void deveEvictarCachePorEmail_aposDesativacao() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        searchService.searchByEmail("fulano@email.com");
        searchService.searchByEmail("fulano@email.com");


        registerService.deactivateUser(registrado.getId());

        assertNull(cacheByEmail.get("fulano@email.com"));
    }

    @Test
    void devePopularCachePorId_aposUpdateUser() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        String id = registrado.getId();

        searchService.searchById(id);
        searchService.searchById(id);


        registerService.updateUser(buildDTO("Novo Nome", "fulano@email.com", null), id);

        assertNotNull(cacheById.get(id));
        assertEquals("Novo Nome",
                ((UserResponseDTO) cacheById.get(id).get()).getName());
    }

    @Test
    void deveEvictarCachePorEmail_aposUpdateUser() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        String id = registrado.getId();

        searchService.searchByEmail("fulano@email.com");

        registerService.updateUser(buildDTO("Novo Nome", "fulano@email.com", null), id);

        assertNull(cacheByEmail.get("fulano@email.com"));
    }
}
