package com.users.notificationservice.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Unitário do filtro de servlet puro (ADR-006/ADR-015) — sem Spring Security neste serviço,
 * o {@code FilterRegistrationBean} não é carregado por {@code @WebMvcTest}, então a
 * verificação de enforcement do header é feita isoladamente aqui (mesmo padrão do
 * {@code InternalTokenFilterTest} do user-service, classe idêntica).
 */
@ExtendWith(MockitoExtension.class)
class InternalTokenFilterTest {

    private static final String TOKEN = "segredo-interno";

    private final InternalTokenFilter filter = new InternalTokenFilter(TOKEN, new ObjectMapper());

    @Test
    void deveIgnorarFiltro_quandoRotaNaoEhInternal() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/actuator/health");

        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void deveAplicarFiltro_quandoRotaEhInternal() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/internal/notifications/email-verification");

        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    void devePassar_quandoTokenCorreto() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-Internal-Token")).thenReturn(TOKEN);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void deveBloquearCom403_quandoTokenErrado() throws Exception {
        StringWriter body = assertForbidden("token-errado");
        assertTrue(body.toString().contains("Forbidden"));
    }

    @Test
    void deveBloquearCom403_quandoHeaderAusente() throws Exception {
        assertForbidden(null);
    }

    private StringWriter assertForbidden(String providedToken) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-Internal-Token")).thenReturn(providedToken);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpStatus.FORBIDDEN.value());
        verify(response).setContentType("application/problem+json");
        verify(chain, never()).doFilter(any(), any());
        return sw;
    }
}
