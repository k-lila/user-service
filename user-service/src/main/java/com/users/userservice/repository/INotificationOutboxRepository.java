package com.users.userservice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.domain.NotificationType;

@Repository
public interface INotificationOutboxRepository extends MongoRepository<NotificationOutbox, String> {

    Optional<NotificationOutbox> findByTokenHash(String tokenHash);

    List<NotificationOutbox> findByUserIdAndTypeAndStatusIn(
            String userId, NotificationType type, List<NotificationStatus> statuses);
}
