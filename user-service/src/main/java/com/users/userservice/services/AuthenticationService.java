package com.users.userservice.services;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.AuthDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Serviço de autenticação", description = "Serviço de autenticação de usuários")
@Service
public class AuthenticationService {
    private final IUserRepository userRepository;
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationService.class);

    public AuthenticationService(IUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AuthDTO getUserByEmail(String email) {
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isEmpty() || user.get().getActive() == false) {
            LOGGER.info(
                "| não encontrado | email: {}",
                email
            );
            throw new DomainEntityNotFound(User.class,"email" , email);
        }
        AuthDTO authDTO = new AuthDTO();
        authDTO.setId(user.get().getId());
        authDTO.setEmail(user.get().getEmail());
        authDTO.setPasswordHash(user.get().getPasswordHash());
        authDTO.setActive(user.get().getActive());
        authDTO.setRoles(user.get().getRoles());

        LOGGER.info(
            "| enviando auth info | email: {}",
            email
        );

        return authDTO;
    }
}
