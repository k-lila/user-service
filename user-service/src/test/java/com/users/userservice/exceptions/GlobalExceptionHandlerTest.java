package com.users.userservice.exceptions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void deveRetornar404_quandoDomainEntityNotFound() {
        DomainEntityNotFound ex = new DomainEntityNotFound(Object.class, "id", "x");

        ProblemDetail pd = handler.handleDomainEntityNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND.value(), pd.getStatus());
        assertEquals("Not Found", pd.getTitle());
        assertEquals(ex.getMessage(), pd.getDetail());
    }

    @Test
    void deveRetornar409_quandoEmailJaCadastrado() {
        ProblemDetail pd = handler.handleEmailAlreadyRegistered(
                new EmailAlreadyRegisteredException("fulano@email.com"));

        assertEquals(HttpStatus.CONFLICT.value(), pd.getStatus());
        assertEquals("Conflict", pd.getTitle());
        assertEquals("Email already registered", pd.getDetail());
    }

    @Test
    void deveRetornar400_quandoIllegalArgument() {
        ProblemDetail pd = handler.handleIllegalArgument(
                new IllegalArgumentException("argumento ruim"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals("Bad Request", pd.getTitle());
        assertEquals("argumento ruim", pd.getDetail());
    }

    @Test
    void deveRetornar400ComErros_quandoValidacaoFalha() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("user", "email", "não pode ser nulo");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ProblemDetail pd = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals("Validation Failed", pd.getTitle());
        assertNotNull(pd.getProperties());
        assertEquals(
                List.of(java.util.Map.of("field", "email", "message", "não pode ser nulo")),
                pd.getProperties().get("errors"));
    }

    @Test
    void deveRelancar_quandoAccessDenied() {
        AccessDeniedException ex = new AccessDeniedException("negado");

        assertThrows(AccessDeniedException.class, () -> handler.handleAccessDenied(ex));
    }

    // ADR-021: sem este handler a exceção cai no catch-all Exception (500 + log ERROR), porque
    // este advice não estende ResponseEntityExceptionHandler e o ExceptionHandlerExceptionResolver
    // roda antes do DefaultHandlerExceptionResolver.
    @Test
    void deveRetornar405_quandoMetodoNaoSuportado() {
        ProblemDetail pd = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", List.of("PUT")));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), pd.getStatus());
        assertEquals("Method Not Allowed", pd.getTitle());
        assertEquals("Método não suportado", pd.getDetail());
    }

    @Test
    void deveRetornar500Generico_quandoExcecaoNaoTratada() {
        ProblemDetail pd = handler.handleGeneric(new RuntimeException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), pd.getStatus());
        assertEquals("Internal Server Error", pd.getTitle());
        assertEquals("Erro interno", pd.getDetail());
    }
}
