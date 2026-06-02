package com.users.userservice.services;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.EmailAlreadyRegisteredException;
import com.users.userservice.repository.IUserRepository;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Serviço de registro", description = "Serviços de registro de usuários")
@Service
public class RegisterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterService.class);
    private final IUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheService cacheService;

    public RegisterService(IUserRepository iUserRepository, PasswordEncoder passwordEncoder, CacheService cacheService) {
        this.userRepository = iUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.cacheService = cacheService;
    }

    public UserResponseDTO registerUser(@Valid UserRequestDTO userDTO) {
        if (userRepository.findByEmail(userDTO.getEmail()).isPresent()) {
            LOGGER.info(
                "| email já cadastrado | email: {}",
                userDTO.getEmail()
            );
            throw new EmailAlreadyRegisteredException(userDTO.getEmail());
        }
        User user = new User();
        user.setName(userDTO.getName());
        user.setEmail(userDTO.getEmail());
        user.setPasswordHash(passwordEncoder.encode(userDTO.getPassword()));
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
        User existingUser = userRepository.findById(userID)
            .orElseThrow(() -> new DomainEntityNotFound(User.class, "ID", userID));
        Optional<User> userWithEmail = userRepository.findByEmail(userDTO.getEmail());


        if (userWithEmail.isPresent() && !userWithEmail.get().getId().equals(userID)) {
            LOGGER.info(
                "| email já cadastrado | email: {}",
                userDTO.getEmail()
            );
            throw new EmailAlreadyRegisteredException(userDTO.getEmail());
        }
        String oldMail = existingUser.getEmail();
        existingUser.setName(userDTO.getName());
        existingUser.setEmail(userDTO.getEmail());
        User updated = userRepository.save(existingUser);
        UserResponseDTO updatedDTO = UserResponseDTO.toResponseDTO(updated);
        cacheService.evictByEmail(oldMail);
        cacheService.evictByEmailAuth(oldMail);
        cacheService.evictById(userID);
        cacheService.putById(userID, updatedDTO);
        cacheService.putByEmail(updated.getEmail(), updatedDTO);

        LOGGER.info(
            "| usuário atualizado | nome: {}, ID: {}",
            updated.getName(),
            updated.getId()
        );
        return UserResponseDTO.toResponseDTO(updated);
    }

    public void deactivateUser(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        User toDeactivate = user.get();
        String email = toDeactivate.getEmail();
        toDeactivate.setActive(false);
        cacheService.evictByEmail(email);
        cacheService.evictByEmailAuth(email);
        cacheService.evictById(userID);
        userRepository.save(toDeactivate);
        LOGGER.info(
            "| usuário desativado | ID: {}",
            userID
        );
    }

    public void deleteUser(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        String email = user.get().getEmail();
        cacheService.evictByEmail(email);
        cacheService.evictByEmailAuth(email);
        cacheService.evictById(userID);
        userRepository.delete(user.get());
        LOGGER.info(
            "| usuário deletado | ID: {}",
            userID
        );
    }

}
