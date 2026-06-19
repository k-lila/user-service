package com.users.userservice.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.users.userservice.domain.AuditLog;

@Repository
public interface IAuditLogRepository extends MongoRepository<AuditLog, String> {
    // Aproveita o índice composto (targetUserId, timestamp desc) — ver AuditLog.
    Page<AuditLog> findByTargetUserId(String targetUserId, Pageable pageable);
}
