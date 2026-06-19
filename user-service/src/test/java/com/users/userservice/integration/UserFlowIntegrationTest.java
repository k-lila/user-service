package com.users.userservice.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
        dto.setTermsAccepted(true);
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
        // Consentimento LGPD persistido no cadastro (aceite versionado com timestamp).
        assertNotNull(encontrado.getConsentAcceptedAt());
        assertEquals("v1", encontrado.getTermsVersion());
        // ADR-015: e-mail nasce não verificado; só vira true após confirmação via token.
        assertFalse(encontrado.getEmailVerified());
        assertNull(encontrado.getEmailVerifiedAt());
    }

    @Test
    void deveLancarEmailAlreadyRegisteredException_quandoEmailDuplicadoNoBanco() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));

        assertThrows(EmailAlreadyRegisteredException.class, () ->
                registerService.registerUser(buildDTO("Ciclano", "fulano@email.com", "outrasenha")));
    }

    // Verifica o índice único de e-mail no nível do banco (C1), driblando a
    // pré-checagem do serviço: insert direto pelo repositório com e-mail repetido.
    @Test
    void deveLancarDuplicateKeyException_quandoIndiceUnicoDeEmailAtivo() {
        userRepository.insert(buildEntity("Fulano", "dup@email.com"));

        assertThrows(DuplicateKeyException.class, () ->
                userRepository.insert(buildEntity("Ciclano", "dup@email.com")));
    }

    private User buildEntity(String nome, String email) {
        User user = new User();
        user.setName(nome);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRoles(Set.of("USER"));
        user.setRegistrationDate(Instant.now());
        user.setActive(true);
        return user;
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

    // ADR-001: após soft-delete, o usuário some das leituras (busca por ID/email e listagem).
    @Test
    void deveOcultarUsuarioInativo_naBuscaPorId() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        registerService.deactivateUser(registrado.getId());

        assertThrows(DomainEntityNotFound.class, () -> searchService.searchById(registrado.getId()));
    }

    // ADR-001
    @Test
    void deveOcultarUsuarioInativo_naBuscaPorEmail() {
        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));
        registerService.deactivateUser(registrado.getId());

        assertThrows(DomainEntityNotFound.class, () -> searchService.searchByEmail("fulano@email.com"));
    }

    // ADR-001
    @Test
    void deveExcluirUsuariosInativos_daListagem() {
        registerService.registerUser(buildDTO("Fulano", "fulano@email.com", "senha123"));
        UserResponseDTO paraInativar = registerService.registerUser(
                buildDTO("Ciclano", "ciclano@email.com", "senha456"));
        registerService.deactivateUser(paraInativar.getId());

        Page<UserResponseDTO> pagina = searchService.searchAll(PageRequest.of(0, 10));

        assertEquals(1, pagina.getTotalElements());
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
