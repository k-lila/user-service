package com.users.userservice.domain;

/**
 * Operações sobre dado pessoal registradas na trilha de auditoria (LGPD, item 1.4 RELATORIOA).
 * Distinta do log operacional SLF4J: é registro de negócio durável de
 * <i>quem acessou/alterou/apagou qual dado de qual titular, quando</i>.
 */
public enum AuditAction {
    /** Cadastro de novo usuário (auto-registro). */
    REGISTER,
    /** Alteração de dados do próprio usuário. */
    UPDATE,
    /** Desativação (soft-delete) por um ADMIN sobre outro titular. */
    SOFT_DELETE_ADMIN,
    /** Remoção definitiva (hard-delete) por um ADMIN. */
    HARD_DELETE_ADMIN,
    /** Auto-remoção (soft-delete) do próprio usuário. */
    SOFT_DELETE_SELF,
    /** Auto-remoção definitiva (hard-delete) do próprio usuário. */
    HARD_DELETE_SELF,
    /** Leitura de credencial via canal interno (auth-server, ator SYSTEM). */
    READ_INTERNAL_CREDENTIAL,
    /**
     * Leitura dos dados de um titular distinto do solicitante.
     *
     * @deprecated Não mais emitido: as leituras públicas por id/e-mail viraram ADMIN-only
     *     ({@link #ADMIN_READ_USER}, fix do G1/IDOR, ADR-016). Mantido para desserializar
     *     registros históricos de {@code auditLogs}.
     */
    @Deprecated
    READ_CROSS_SUBJECT,
    /** Leitura administrativa de um titular por id/e-mail (AdminController, ADR-016 — fix G1). */
    ADMIN_READ_USER,
    /**
     * Leitura administrativa em massa via listagem paginada (AdminController — fix G13).
     * Emitida <b>uma vez por titular retornado na página</b>, e não uma vez por requisição:
     * é o que faz a listagem aparecer no histórico de cada titular
     * ({@code GET /v1/admin/users/{id}/audit-logs}), respondendo "quem acessou o meu dado?".
     */
    ADMIN_LIST_USERS,
    /** Concessão da role ADMIN a um titular (AdminController, ADR-014). */
    ROLE_GRANT,
    /** Revogação da role ADMIN de um titular (AdminController, ADR-014). */
    ROLE_REVOKE,
    /** Confirmação de e-mail via token de verificação (ADR-015). Ator: o próprio titular. */
    EMAIL_VERIFIED
}
