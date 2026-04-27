package com.users.userservice.services;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final PasswordEncoder passwordEncoder;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    public RegisterService(IUserRepository iUserRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = iUserRepository;
        this.passwordEncoder = passwordEncoder;
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
        user.setPasswordHash(passwordEncoder.encode(userDTO.getPasswordHash()));
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

    @CachePut(value = "users", key = "#userID")
    public UserResponseDTO updateUser(@Valid UserRequestDTO userDTO, String userID) {
        if (!userRepository.existsById(userID)) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }

        User existingUser = userRepository.findById(userID).get();

        if (!existingUser.getEmail().equals(userDTO.getEmail())) {
            LOGGER.info(
                "| email já cadastrado | email: {}",
                userDTO.getEmail()
            );
            throw new RuntimeException("Email já cadastrado");
        }

        User toUpdate = userRepository.findById(userID).get();
        String oldMail = toUpdate.getEmail();
        toUpdate.setName(userDTO.getName());
        toUpdate.setEmail(userDTO.getEmail());
        User updated = userRepository.save(toUpdate);
        cacheManager.getCache("users").evict(oldMail);
        cacheManager.getCache("users").evict(updated.getEmail());
        LOGGER.info(
            "| usuário atualizado | ID: {}",
            updated.getName(),
            updated.getId()
        );
        return UserResponseDTO.toResponseDTO(updated);
    }

    @CacheEvict(value = "users", key = "#userID")
    public void deleteUser(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        userRepository.delete(user.get());
        cacheManager.getCache("users").evict(user.get().getEmail());
        LOGGER.info(
            "| usuário deletado | ID: {}",
            userID
        );
    }

    @CacheEvict(value = "users", key = "#userID")
    public void deactivateUser(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        User toDeactivate = user.get();
        toDeactivate.setActive(false);
        userRepository.save(toDeactivate);
        cacheManager.getCache("users").evict(user.get().getEmail());
        LOGGER.info(
            "| usuário desativado | ID: {}",
            userID
        );
    }
}
