package com.users.userservice.exceptions;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    // Método não suportado no path → 405, não 500 (ADR-021). Sem este handler a exceção cai no
    // catch-all Exception abaixo: este @RestControllerAdvice não estende
    // ResponseEntityExceptionHandler, e o ExceptionHandlerExceptionResolver roda ANTES do
    // DefaultHandlerExceptionResolver — então quem responderia 405 nunca é alcançado. O sintoma
    // é 500 + log ERROR a cada probe/scanner que acerte um path existente com o verbo errado
    // (ex.: GET /v1/users, cuja rota foi removida mas segue mapeada para PUT).
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        LOGGER.warn("| 405 | método não suportado | {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.METHOD_NOT_ALLOWED, "Método não suportado");
        pd.setTitle("Method Not Allowed");
        return pd;
    }

    // Path sem handler → 404, não 500. Mesma raiz do 405 acima: o catch-all Exception intercepta
    // antes do DefaultHandlerExceptionResolver. O gatilho concreto é "/actuator/**", que continua
    // no permitAll() do SecurityConfig (ver o comentário lá) mas deixou de ser mapeado na porta de
    // tráfego quando o actuator foi para a 8181 (gap G14): o request atravessa a segurança, não
    // acha recurso e virava 500 + stack trace em ERROR a cada healthcheck errado ou scanner.
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException e) {
        LOGGER.warn("| 404 | recurso inexistente | {}", e.getResourcePath());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Recurso não encontrado");
        pd.setTitle("Not Found");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception e) {
        LOGGER.error("| 500 | erro não tratado", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");
        pd.setTitle("Internal Server Error");
        return pd;
    }
}
