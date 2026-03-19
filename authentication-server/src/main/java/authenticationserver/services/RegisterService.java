package authenticationserver.services;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import authenticationserver.clients.IUserClient;
import authenticationserver.dtos.RegisterRequestDTO;
import authenticationserver.dtos.RegisterResponseDTO;
import feign.FeignException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Serviço de registro de usuários", description = "Serviço responsável por registrar novos usuários no sistema.")
@Service
public class RegisterService {

    private final IUserClient userClient;
    private final PasswordEncoder passwordEncoder;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterService.class);


    public RegisterService(IUserClient userClient, PasswordEncoder passwordEncoder) {
        this.userClient = userClient;
        this.passwordEncoder = passwordEncoder;
    }

    public RegisterResponseDTO registerUser(@Valid RegisterRequestDTO registerRequest) {
        try {
            String passwordHash = passwordEncoder.encode(registerRequest.getPasswordHash());
            registerRequest.setPasswordHash(passwordHash);
            RegisterRequestDTO newUser = new RegisterRequestDTO();
            newUser.setName(registerRequest.getName());
            newUser.setEmail(registerRequest.getEmail());
            newUser.setPasswordHash(passwordHash);
            RegisterResponseDTO response = userClient.registerUser(newUser);
            LOGGER.info(
                "| usuário registrado | email: {}",
                registerRequest.getEmail()
            );
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
