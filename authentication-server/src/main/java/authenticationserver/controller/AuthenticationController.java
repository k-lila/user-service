package authenticationserver.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import authenticationserver.dtos.LoginRequestDTO;
import authenticationserver.dtos.RegisterRequestDTO;
import authenticationserver.dtos.RegisterResponseDTO;
import authenticationserver.dtos.TokenDTO;
import authenticationserver.services.AuthenticationService;
import authenticationserver.services.RegisterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(
    name = "Controller",
    description = "Endpoints do serviço de autenticação"
)
@RestController
@RequestMapping("/authentication")
public class AuthenticationController {
    private final AuthenticationService authenticationService;
    private final RegisterService registerService;
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationController.class);

    public AuthenticationController(AuthenticationService authenticationService, RegisterService registerService) {
        this.authenticationService = authenticationService;
        this.registerService = registerService;
    }

    @Operation(summary = "Autenticar usuário e gerar token JWT")
    @PostMapping("/login")
    public TokenDTO login(@RequestBody @Valid LoginRequestDTO loginRequest ) {
        LOGGER.info(
            "| REQUISIÇÃO DE LOGIN | email: {}",
            loginRequest.getEmail()
        );
        TokenDTO token = authenticationService.authenticate(loginRequest.getEmail(), loginRequest.getPassword());
        return token;
    }

    @Operation(summary = "Registrar um novo usuário")
    @PostMapping("/register")
    public RegisterResponseDTO register(@RequestBody @Valid RegisterRequestDTO registerRequest) {
        LOGGER.info(
            "| REQUISIÇÃO DE REGISTRO | email: {}",
            registerRequest.getEmail()
        );
        return registerService.registerUser(registerRequest);
    }

}
