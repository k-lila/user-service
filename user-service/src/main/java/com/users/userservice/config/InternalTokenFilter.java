package com.users.userservice.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Guarda o canal interno (/internal/**): exige o shared secret X-Internal-Token,
// fechando o acesso direto à porta do user-service. Demais rotas seguem pelo JWT.
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Token";
    private final String expectedToken;
    private final ObjectMapper mapper;

    public InternalTokenFilter(String expectedToken, ObjectMapper mapper) {
        this.expectedToken = expectedToken;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (!matches(provided)) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Forbidden");
            pd.setTitle("Forbidden");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            mapper.writeValue(response.getWriter(), pd);
            return;
        }
        filterChain.doFilter(request, response);
    }

    // Comparação em tempo constante para não vazar o token via timing.
    private boolean matches(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
