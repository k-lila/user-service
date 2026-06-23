package com.users.userservice.exceptions;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainEntityNotFound.class)
    public ProblemDetail handleDomainEntityNotFound(DomainEntityNotFound e) {
        LOGGER.warn("| 404 | entidade não encontrada | {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Not Found");
        return pd;
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail handleEmailAlreadyRegistered(EmailAlreadyRegisteredException e) {
        LOGGER.warn("| 409 | email já cadastrado");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email already registered");
        pd.setTitle("Conflict");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        LOGGER.warn("| 400 | argumento inválido | {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Bad Request");
        return pd;
    }

    @ExceptionHandler(SelfRoleRevocationException.class)
    public ProblemDetail handleSelfRoleRevocation(SelfRoleRevocationException e) {
        LOGGER.warn("| 409 | auto-revogação de ADMIN bloqueada | {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Conflict");
        return pd;
    }

    // Token inexistente/expirado/já usado (ADR-015) — mensagem sempre genérica (anti-enumeração),
    // mas com status 400 correto (entrada do usuário, não erro do servidor).
    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ProblemDetail handleInvalidVerificationToken(InvalidVerificationTokenException e) {
        LOGGER.warn("| 400 | token de verificação inválido");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Bad Request");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
            .map(err -> Map.of("field", err.getField(), "message", err.getDefaultMessage()))
            .toList();
        LOGGER.warn("| 400 | validação falhou | {}", errors);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setTitle("Validation Failed");
        pd.setProperty("errors", errors);
        return pd;
    }

    // Deixa o Spring Security traduzir negações de acesso (403) — não devem virar 500.
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception e) {
        LOGGER.error("| 500 | erro não tratado", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");
        pd.setTitle("Internal Server Error");
        return pd;
    }
}
