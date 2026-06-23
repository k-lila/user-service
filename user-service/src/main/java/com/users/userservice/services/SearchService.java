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
        // Só usuários ativos: soft-deleted (active=false) ficam ocultos nas leituras. Ver ADR-001.
        Page<UserResponseDTO> mapped = userRepository.findByActiveTrue(pageable).map((user) -> {
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
        // Usuário inativo (soft-deleted) é tratado como inexistente nas leituras. Ver ADR-001.
        if (user.isEmpty() || !user.get().getActive()) {
            LOGGER.warn("| busca por ID | inexistente ou inativo | ID: {}", userID);
            throw new DomainEntityNotFound(User.class, "ID", userID);
        }
        User found = user.get();
        LOGGER.info("| busca por ID | encontrado | ID: {}", found.getId());
        return UserResponseDTO.toResponseDTO(found);
    }

    // Leitura por e-mail no nível de serviço, mantida como suporte ao cache usersByEmail
    // (populado/evictado por RegisterService/EmailVerificationService). A rota HTTP pública de
    // busca por e-mail foi removida — a leitura por e-mail virou ADMIN-only no AdminController
    // (AdminService.findByEmail, sem cache), parte do fix do G1/IDOR (ADR-016).
    @Cacheable(value = "usersByEmail", key = "#email")
    public UserResponseDTO searchByEmail(String email) {
        Optional<User> user = userRepository.findByEmail(email);
        // Usuário inativo (soft-deleted) é tratado como inexistente nas leituras. Ver ADR-001.
        if (user.isEmpty() || !user.get().getActive()) {
            LOGGER.warn("| busca por email | inexistente ou inativo");
            throw new DomainEntityNotFound(User.class, "Email", email);
        }
        User found = user.get();
        LOGGER.info("| busca por email | encontrado | ID: {}", found.getId());
        return UserResponseDTO.toResponseDTO(found);
    }
}
