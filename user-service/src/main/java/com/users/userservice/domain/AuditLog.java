package com.users.userservice.domain;

import java.time.Instant;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

/**
 * Entrada da trilha de auditoria de acesso a dado pessoal (LGPD, item 1.4 RELATORIOA).
 *
 * <p>Registro de negócio durável e distinto do log operacional SLF4J: responde
 * <i>quem (ator) acessou/alterou/apagou (ação) qual dado de qual titular (target), quando</i>.
 * O {@code targetEmail} é sempre mascarado ({@code LogUtils.maskEmail}). O {@code correlationId}
 * (traceId B3) liga a entrada ao trace completo — inclusive ao log de borda do gateway, que é
 * onde o IP real do cliente é registrado (no user-service o IP seria o do gateway, enganoso).
 *
 * <p>Índice composto {@code (targetUserId, timestamp desc)} para a consulta natural "histórico de
 * acesso de um titular".
 */
@Document(collection = "auditLogs")
@CompoundIndex(name = "target_ts_idx", def = "{'targetUserId': 1, 'timestamp': -1}")
@Getter
@Setter
public class AuditLog {

    @Id
    private String id;

    /** Quando a operação ocorreu. */
    private Instant timestamp;

    /** Operação realizada. */
    private AuditAction action;

    /** Tipo do ator: USER, ADMIN ou SYSTEM (canal interno). */
    private String actorType;

    /** ID do ator (null quando SYSTEM). */
    private String actorUserId;

    /** Papéis do ator no momento da ação (null quando SYSTEM). */
    private Set<String> actorRoles;

    /** ID do titular cujo dado foi acessado/alterado (quando aplicável). */
    private String targetUserId;

    /** E-mail do titular, mascarado (quando aplicável). */
    private String targetEmail;

    /** traceId B3 corrente, para correlação com logs/Zipkin (quando disponível). */
    private String correlationId;
}
