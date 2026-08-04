package com.users.userservice.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.users.userservice.domain.User;

@Repository
public interface IUserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);

    // findByActiveTrue(Pageable) foi removido junto da rota GET /v1/users (ADR-021): sustentava
    // só a listagem pública. A listagem administrativa (AdminService.listAllUsers) usa
    // MongoTemplate com filtros próprios e inclui inativos — não passa por aqui.
}
