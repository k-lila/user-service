package com.users.userservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(
    name = "Controller",
    description = "Endpoints do serviço de usuários"
)
@RestController
@RequestMapping(value = "users")
public class UserController {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserController.class);
    private RegisterService registerService;
    private SearchService searchService;

    @Autowired
    public UserController(RegisterService registerService, SearchService searchService) {
        this.registerService = registerService;
        this.searchService = searchService;
    }

    @Operation(summary = "Lista todos os usuários")
    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Page<UserResponseDTO>> searchAll(Pageable pageable) {
        LOGGER.info(
            "| GET | buscar todos | tamanho e número da página: {}x{}",
            pageable.getPageSize(),
            pageable.getPageNumber()
        );
        Page<UserResponseDTO> users = searchService.searchAll(pageable);
        return ResponseEntity.ok(users);
    }

    @Operation(summary = "Buscar usuário por ID")
    @GetMapping(value = "/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponseDTO> searchById(@PathVariable(value = "id", required = true) String userID) {
        LOGGER.info(
            "| GET | buscar por ID | ID: {}",
            userID
        );
        UserResponseDTO user = searchService.searchById(userID);
        return ResponseEntity.ok(user);
    }

    @Operation(summary = "Buscar usuário por Email")
    @GetMapping(value = "/email/{email}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponseDTO> searchByEmail(@PathVariable(value = "email", required = true) String email) {
        LOGGER.info("| GET | busca por email");
        UserResponseDTO user = searchService.searchByEmail(email);
        return ResponseEntity.ok(user);
    }

    @Operation(summary = "Modificar um usuário")
    @PutMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponseDTO> updateUser(@RequestBody @Valid UserRequestDTO userDTO, String userID) {
        LOGGER.info(
            "| PUT | nome: {}, id: {}",
            userDTO.getName(),
            userID
        );
        UserResponseDTO updated = registerService.updateUser(userDTO, userID);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Desativar um usuário")
    @DeleteMapping(value = "/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> removeUser(@PathVariable(value = "id", required = true) String userID) {
        LOGGER.info(
            "| DESATIVADAR | id: {}",
            userID
        );
        registerService.deleteUser(userID);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Deletar um usuário")
    @DeleteMapping(value = "/del/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable(value = "id", required = true) String userID) {
        LOGGER.info(
            "| DELETAR | id: {}",
            userID
        );
        registerService.deleteUser(userID);
        return ResponseEntity.noContent().build();
    }

}
