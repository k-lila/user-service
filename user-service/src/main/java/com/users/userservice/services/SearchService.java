package com.users.userservice.services;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.repository.IUserRepository;

import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Serviço de busca", description = "Serviços de busca de usuários")
@Service
public class SearchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchService.class);
    private IUserRepository userRepository;

    @Autowired
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
            userRepository.count()
        );
        return mapped;
    }

    public UserResponseDTO searchById(String userID) {
        Optional<User> user = userRepository.findById(userID);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"ID" , userID);
        }
        User found = user.get();
        LOGGER.info(
            "| usuário encontrado | ID: {}",
            found.getId()
        );
        return UserResponseDTO.toResponseDTO(found);
    }

    public UserResponseDTO searchByEmail(String email) {
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isEmpty()) {
            throw new DomainEntityNotFound(User.class,"Email" , email);
        }
        User found = user.get();
        LOGGER.info(
            "| usuário encontrado | ID: {}",
            found.getId()
        );
        return UserResponseDTO.toResponseDTO(found);    
    }

}
