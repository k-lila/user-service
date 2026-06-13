package com.users.gateway.filter;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import reactor.core.publisher.Mono;

@Component
public class CorrelationIdFilter implements GlobalFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    private final Tracer tracer;

    public CorrelationIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Alinha o correlationId ao traceId B3 (id único ponta a ponta), preservando um
        // X-Correlation-ID já recebido (id de cliente externo). Sem header e sem span,
        // cai num UUID — degradação graciosa para nunca quebrar o filtro.
        String correlationId = exchange.getRequest()
                .getHeaders()
                .getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null) {
            correlationId = currentTraceId();
        }
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        LOGGER.info(
            "| requisição recebida | {} {} | correlationId: {}",
            exchange.getRequest().getMethod(),
            exchange.getRequest().getPath().value(),
            correlationId
        );
        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}