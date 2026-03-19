package com.users.userservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.users.userservice.dtos.AuthDTO;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.services.AuthenticationService;
import com.users.userservice.services.RegisterService;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Controller interno", description = "Endpoint interno - serviço de autenticação de usuários")
@Hidden
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {
    private final AuthenticationService authenticationService;
    private final RegisterService registerService;
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalUserController.class);

    public InternalUserController(AuthenticationService authenticationService, RegisterService registerService) {
        this.authenticationService = authenticationService;
        this.registerService = registerService;
    }

    @Operation(summary = "Autenticar um usuário")
    @GetMapping("/email/{email}")
    public ResponseEntity<AuthDTO> findByEmail(@PathVariable String email) {
        LOGGER.info(
            "| AUTH REQUEST | email: {}",
            email
        );
        AuthDTO authenticated = authenticationService.getUserByEmail(email);
        return ResponseEntity.ok(authenticated);
    }

    @Operation(summary = "Registrar novo usuário")
    @PostMapping
    public ResponseEntity<UserResponseDTO> registerUser(@RequestBody @Valid UserRequestDTO userDTO) {
        LOGGER.info(
            "| POST | nome: {}",
            userDTO.getName()
        );
        UserResponseDTO registered = registerService.registerUser(userDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

}