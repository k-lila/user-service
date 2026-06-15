package com.users.userservice.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.users.userservice.domain.User;

@Repository
public interface IUserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);

    // Listagem pública só de usuários ativos (soft-deleted ficam ocultos). Ver ADR-001.
    Page<User> findByActiveTrue(Pageable pageable);
}
