package com.users.userservice.exceptions;

// Token de verificação de e-mail inválido, expirado ou inexistente. Mensagem sempre
// genérica ao chamador (não revela qual motivo específico — evita oráculo de enumeração).
public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("Token de verificação inválido ou expirado");
    }
}
