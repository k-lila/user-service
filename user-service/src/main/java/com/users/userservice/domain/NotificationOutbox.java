package com.users.userservice.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

/**
 * Registro de outbox para notificações disparadas por evento (ADR-015) — sem poller/scan
 * periódico: o processamento é disparado pelo próprio evento (cadastro, reenvio).
 *
 * <p>{@code tokenHash} é o único artefato persistido do token opaco de verificação (o token em
 * claro nunca é salvo). {@code purgeAt} é um campo de retenção <b>separado</b> de
 * {@code expiresAt} (TTL index do Mongo aplicado em {@code purgeAt}, não em {@code expiresAt}):
 * aplicar o TTL direto sobre {@code expiresAt} apagaria também registros {@code CONFIRMED}/
 * {@code SENT} pouco depois da expiração do token, perdendo o histórico de auditoria do
 * outbox. {@code purgeAt} dá uma janela de retenção maior (ver ADR-015).
 */
@Document(collection = "notificationOutbox")
@CompoundIndexes({
    @CompoundIndex(name = "userId_type_status", def = "{'userId': 1, 'type': 1, 'status': 1}")
})
@Getter
@Setter
public class NotificationOutbox {

    @Id
    private String id;

    @Indexed
    private String userId;

    private NotificationType type;

    @Indexed(unique = true)
    private String tokenHash;

    private Instant expiresAt;

    private NotificationStatus status;

    private Instant createdAt;

    private Instant lastAttemptAt;

    private int attempts;

    // TTL index do Mongo: documentos são removidos automaticamente quando purgeAt < now.
    // Maior que expiresAt para preservar histórico de auditoria do outbox por uma janela
    // adicional após a expiração/confirmação do token (ver ADR-015, fechamento do C1).
    @Indexed(name = "purgeAt_ttl", expireAfterSeconds = 0)
    private Instant purgeAt;
}
