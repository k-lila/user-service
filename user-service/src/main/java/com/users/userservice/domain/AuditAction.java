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
    /** Leitura de credencial via canal interno (auth-server, ator SYSTEM). */
    READ_INTERNAL_CREDENTIAL,
    /** Leitura dos dados de um titular distinto do solicitante. */
    READ_CROSS_SUBJECT
}
