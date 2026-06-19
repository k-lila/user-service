package com.users.userservice.exceptions;

/**
 * Lançada quando um ADMIN tenta remover a própria role ADMIN via
 * {@code PATCH /v1/admin/users/{id}/roles} (AC-09, ADR-014).
 *
 * <p>Bloqueia o lockout operacional: a checagem é feita contra o estado de roles
 * <b>persistido no MongoDB</b> (não o JWT, que pode estar stale por até o TTL do cache
 * {@code authByEmail}), comparado ao claim {@code userID} do ator. Mapeada para
 * {@code 409 Conflict} em {@code GlobalExceptionHandler} — distinto de 400 (payload
 * malformado), pois é conflito com uma invariante de estado/operação.
 */
public class SelfRoleRevocationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SelfRoleRevocationException(String userId) {
        super("Admin user " + userId + " cannot revoke its own ADMIN role");
    }
}
