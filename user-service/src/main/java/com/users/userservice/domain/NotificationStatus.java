package com.users.userservice.domain;

/** Estado do envio de notificação no outbox (notificationOutbox). */
public enum NotificationStatus {
    /** Outbox criado, envio ainda não confirmado (chamada assíncrona em voo ou ainda não disparada). */
    PENDING,
    /** Envio confirmado com sucesso pelo notification-service. */
    SENT,
    /** Envio falhou (notification-service indisponível, fallback do circuit breaker, 502/400). */
    FAILED,
    /** Token confirmado pelo titular — fecha o ciclo de vida do outbox. */
    CONFIRMED,
    /** Substituído por um outbox mais novo (ex.: reenvio) — o token antigo deixa de ser válido. */
    SUPERSEDED
}
