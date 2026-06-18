package com.users.userservice.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.users.userservice.domain.AuditLog;

@Repository
public interface IAuditLogRepository extends MongoRepository<AuditLog, String> {
}
