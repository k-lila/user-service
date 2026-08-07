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

    /**
     * Candidatos à varredura de retry ({@code OutboxRetryService}), do mais antigo para o mais
     * novo. O teto de 100 é deliberado: a varredura roda a cada ciclo e não pode degenerar em
     * leitura ilimitada se o notification-service ficar fora por muito tempo — o excedente é
     * apanhado nos ciclos seguintes.
     */
    List<NotificationOutbox> findTop100ByTypeAndStatusInOrderByCreatedAtAsc(
            NotificationType type, List<NotificationStatus> statuses);

    /**
     * Total de registros já emitidos para o par (titular, tipo) — é o teto de tentativas do
     * retry. Ver o racional em {@code OutboxRetryService}: o contador não pode viver no
     * registro individual, porque cada retry cria um registro novo.
     */
    long countByUserIdAndType(String userId, NotificationType type);
}
