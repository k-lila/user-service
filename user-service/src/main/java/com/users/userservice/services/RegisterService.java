package com.users.userservice.services;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Serviço de registro", description = "Serviços de registro de usuários")
@Service
public class RegisterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterService.class);
    private final IUserRepository userRepository;

    @Autowired
    public RegisterService(IUserRepository iUserRepository) {
        this.userRepository = iUserRepository;
    }

    public UserResponseDTO registerUser(@Valid UserRequestDTO userDTO) {
        if (userRepository.findByEmail(userDTO.getEmail()).isPresent()) {
            LOGGER.info(
                "| email já cadastrado | email: {}",
                userDTO.getEmail()
            );
            throw new RuntimeException("Email já cadastrado");
        }
        User user = new User();
        user.setName(userDTO.getName());
        user.setEmail(userDTO.getEmail());
        user.setPasswordHash(userDTO.getPasswordHash());
        user.setRoles(Set.of("USER"));
        user.setRegistrationDate(Instant.now());
        user.setActive(true);
        User registered = userRepository.insert(user);
        LOGGER.info(
            "| usuário registrado | nome: {}, ID: {}",
            registered.getName(),
            registered.getId() 
        );
        return UserResponseDTO.toResponseDTO(registered);
    }

    public UserResponseDTO updateUser(@Valid UserRequestDTO userDTO, String userID) {
        if (!userRepository.existsById(userID)) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        User toUpdate = userRepository.findById(userID).get();
        toUpdate.setName(userDTO.getName());
        toUpdate.setEmail(userDTO.getEmail());
        User updated = userRepository.save(toUpdate);
        LOGGER.info(
            "| usuário atualizado | ID: {}",
            updated.getName(),
            updated.getId()
        );
        return UserResponseDTO.toResponseDTO(updated);
    }

    public void deleteUser(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        userRepository.delete(user.get());
        LOGGER.info(
            "| usuário deletado | ID: {}",
            userID
        );
    }

    public void deactivateUser(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        User toDeactivate = user.get();
        toDeactivate.setActive(false);
        userRepository.save(toDeactivate);
        LOGGER.info(
            "| usuário desativado | ID: {}",
            userID
        );
    }

}
