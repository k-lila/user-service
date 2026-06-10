package com.users.userservice.services;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

@Service
public class SearchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchService.class);
    private final IUserRepository userRepository;

    public SearchService(IUserRepository iUserRepository) {
        this.userRepository = iUserRepository;
    }

    public Page<UserResponseDTO> searchAll(Pageable pageable) {
        Page<UserResponseDTO> mapped = userRepository.findAll(pageable).map((user) -> {
            return UserResponseDTO.toResponseDTO(user);
        });
        LOGGER.info(
            "| página encontrada | {}x{} | total (usuários): {}",
            pageable.getPageSize(),
            pageable.getPageNumber(),
            mapped.getTotalElements()
        );
        return mapped;
    }

    @Cacheable(value = "usersById", key = "#userID")
    public UserResponseDTO searchById(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            LOGGER.warn("| busca por ID | não encontrado | ID: {}", userID);
            throw new DomainEntityNotFound(User.class, "ID", userID);
        }
        User found = user.get();
        LOGGER.info("| busca por ID | encontrado | ID: {}", found.getId());
        return UserResponseDTO.toResponseDTO(found);
    }

    @Cacheable(value = "usersByEmail", key = "#email")
    public UserResponseDTO searchByEmail(String email) {
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isEmpty()) {
            LOGGER.warn("| busca por email | não encontrado");
            throw new DomainEntityNotFound(User.class, "Email", email);
        }
        User found = user.get();
        LOGGER.info("| busca por email | encontrado | ID: {}", found.getId());
        return UserResponseDTO.toResponseDTO(found);
    }
}
