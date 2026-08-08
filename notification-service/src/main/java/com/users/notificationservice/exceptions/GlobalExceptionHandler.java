package com.users.notificationservice.exceptions;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmailSendFailedException.class)
    public ProblemDetail handleEmailSendFailed(EmailSendFailedException e) {
        LOGGER.warn("| 502 | falha ao enviar e-mail | {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Falha ao enviar e-mail");
        pd.setTitle("Bad Gateway");
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

    // Path sem handler → 404, não 500. Aqui o alcance é maior que no user-service: este serviço
    // não tem Spring Security (só o InternalTokenFilter em /internal/**), então QUALQUER path não
    // mapeado chegava ao catch-all Exception abaixo e virava 500 + stack trace em ERROR — um probe
    // de scanner por segundo enche o log de erro e afoga falha real. O gatilho já observado é
    // /actuator/**, que deixou de ser servido na 8095 quando o actuator foi para a 8181 (gap G14).
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
