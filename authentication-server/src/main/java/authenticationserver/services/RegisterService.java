package authenticationserver.services;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import authenticationserver.clients.IUserClient;
import authenticationserver.dtos.InternalRegisterRequestDTO;
import authenticationserver.dtos.RegisterRequestDTO;
import authenticationserver.dtos.RegisterResponseDTO;
import authenticationserver.dtos.TokenDTO;
import feign.FeignException;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Serviço de registro de usuários", description = "Serviço responsável por registrar novos usuários no sistema.")
@Service
public class RegisterService {

    private final IUserClient userClient;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationService authenticationService;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterService.class);


    public RegisterService(IUserClient userClient, PasswordEncoder passwordEncoder, AuthenticationService authenticationService) {
        this.userClient = userClient;
        this.passwordEncoder = passwordEncoder;
        this.authenticationService = authenticationService;
    }

    public RegisterResponseDTO registerUser(RegisterRequestDTO registerRequest) {
        try {
            InternalRegisterRequestDTO newUser = new InternalRegisterRequestDTO();
            newUser.setName(registerRequest.getName());
            newUser.setEmail(registerRequest.getEmail());
            newUser.setPasswordHash(passwordEncoder.encode(registerRequest.getRawPassword()));
            RegisterResponseDTO response = userClient.registerUser(newUser);
            LOGGER.info(
                "| usuário registrado | email: {}",
                registerRequest.getEmail()
            );
            TokenDTO token = authenticationService.authenticate(registerRequest.getEmail(), registerRequest.getRawPassword());
            response.setToken(token.getToken());
            return response;
        } catch (FeignException e) {
            LOGGER.error(
                "| falha ao registrar usuário | email: {}",
                registerRequest.getEmail()
            );
            throw new RuntimeException("Erro ao registrar usuário: " + e.getMessage());
        }
    }

}
