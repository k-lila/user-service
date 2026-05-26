package com.users.userservice.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

class UserFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired RegisterService registerService;
    @Autowired SearchService searchService;
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
        return dto;
    }

    @Test
    void deveRegistrarEBuscarPorId_quandoDadosValidos() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        UserResponseDTO encontrado = searchService.searchById(registrado.getId());

        assertNotNull(encontrado);
        assertEquals("fulano@email.com", encontrado.getEmail());
        assertEquals("Fulano", encontrado.getName());
    }

    @Test
    void deveLancarExcecao_quandoEmailDuplicadoNoBanco() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));

        assertThrows(RuntimeException.class, () ->
                registerService.registerUser(buildDTO("Ciclano", "fulano@email.com", "outrasenha")));
    }

    @Test
    void deveAtualizarNome_quandoIdExiste() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        registerService.updateUser(buildDTO("Novo Nome", "fulano@email.com", null), registrado.getId());

        User persistido = userRepository.findById(registrado.getId()).orElseThrow();
        assertEquals("Novo Nome", persistido.getName());
        assertEquals("fulano@email.com", persistido.getEmail());
    }

    @Test
    void deveDesativarUsuario_quandoIdExiste() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        registerService.deactivateUser(registrado.getId());

        User dbUser = userRepository.findById(registrado.getId()).orElseThrow();
        assertFalse(dbUser.getActive());
    }

    @Test
    void deveDeletarUsuario_quandoIdExiste() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        registerService.deleteUser(registrado.getId());

        assertFalse(userRepository.existsById(registrado.getId()));
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoIdInexistenteNoDelete() {
        assertThrows(DomainEntityNotFound.class, () ->
                registerService.deleteUser("id-inexistente"));
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoIdInexistenteNaDesativacao() {
        assertThrows(DomainEntityNotFound.class, () ->
                registerService.deactivateUser("id-inexistente"));
    }

    @Test
    void deveLancarDomainEntityNotFound_quandoIdInexistenteNaAtualizacao() {
        assertThrows(DomainEntityNotFound.class, () ->
                registerService.updateUser(buildDTO("Novo", "novo@email.com", null), "id-inexistente"));
    }

    @Test
    void deveRetornarPaginaComUsuarios_quandoExistemRegistros() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));
        registerService.registerUser(buildDTO("Ciclano", "ciclano@email.com", "senha456"));

        Page<UserResponseDTO> pagina = searchService.searchAll(PageRequest.of(0, 10));

        assertEquals(2, pagina.getTotalElements());
        assertFalse(pagina.isEmpty());
    }

    @Test
    void deveRetornarPaginaVazia_quandoNenhumUsuarioCadastrado() {
        Page<UserResponseDTO> pagina = searchService.searchAll(PageRequest.of(0, 10));

        assertTrue(pagina.isEmpty());
    }
}
