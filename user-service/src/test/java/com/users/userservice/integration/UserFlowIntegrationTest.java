package com.users.userservice.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.EmailAlreadyRegisteredException;
import com.users.userservice.repository.IUserRepository;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        assertTrue(encontrado.getActive());           // lacuna 1
        assertNotNull(encontrado.getRegistrationDate()); // lacuna 2
    }

    @Test
    void deveLancarEmailAlreadyRegisteredException_quandoEmailDuplicadoNoBanco() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));

        assertThrows(EmailAlreadyRegisteredException.class, () ->
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

    // lacuna 3
    @Test
    void deveAtribuirRoleUser_quandoUsuarioRegistrado() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        User persistido = userRepository.findById(registrado.getId()).orElseThrow();

        assertTrue(persistido.getRoles().contains("USER"));
        assertEquals(1, persistido.getRoles().size());
    }

    // lacuna 4
    @Test
    void deveHashearSenhaComBcrypt_quandoUsuarioRegistrado() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        User persistido = userRepository.findById(registrado.getId()).orElseThrow();

        assertNotNull(persistido.getPasswordHash());
        assertNotEquals("senha123", persistido.getPasswordHash());
    }

    // lacuna 5
    @Test
    void deveLancarDomainEntityNotFound_quandoIdInexistenteNaBusca() {
        assertThrows(DomainEntityNotFound.class, () ->
                searchService.searchById("id-inexistente"));
    }

    // lacuna 6
    @Test
    void deveBuscarPorEmail_quandoEmailExiste() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));

        UserResponseDTO encontrado = searchService.searchByEmail("fulano@email.com");

        assertNotNull(encontrado);
        assertEquals("Fulano", encontrado.getName());
        assertEquals("fulano@email.com", encontrado.getEmail());
    }

    // lacuna 7
    @Test
    void deveLancarDomainEntityNotFound_quandoEmailInexistente() {
        assertThrows(DomainEntityNotFound.class, () ->
                searchService.searchByEmail("nao@existe.com"));
    }

    // lacuna 8
    @Test
    void deveLancarEmailAlreadyRegisteredException_quandoEmailDeAtualizacaoConflita() {
        UserResponseDTO userA = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        registerService.registerUser(buildDTO("Ciclano", "ciclano@email.com", "senha456"));

        assertThrows(EmailAlreadyRegisteredException.class, () ->
                registerService.updateUser(buildDTO("Fulano", "ciclano@email.com", null), userA.getId()));
    }

    // lacuna 9
    @Test
    void deveRetornarPaginaCorreta_quandoPaginacaoVariada() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));
        registerService.registerUser(buildDTO("Ciclano", "ciclano@email.com", "senha456"));
        registerService.registerUser(buildDTO("Beltrano", "beltrano@email.com", "senha789"));

        Page<UserResponseDTO> pagina = searchService.searchAll(PageRequest.of(1, 2));

        assertEquals(3, pagina.getTotalElements());
        assertEquals(1, pagina.getNumberOfElements());
        assertEquals(1, pagina.getNumber());
    }
}
