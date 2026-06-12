package com.users.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unitários do {@link CorrelationIdFilter}: reaproveita o X-Correlation-ID quando presente
 * e gera um UUID quando ausente, sempre propagando o header na request mutada.
 */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-ID";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void deveReusarCorrelationId_quandoHeaderPresente() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/users/me").header(HEADER, "abc-123"));

        ServerWebExchange forwarded = runFilter(exchange);

        assertThat(forwarded.getRequest().getHeaders().getFirst(HEADER)).isEqualTo("abc-123");
    }

    @Test
    void deveGerarCorrelationIdUuid_quandoHeaderAusente() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/users/me"));

        ServerWebExchange forwarded = runFilter(exchange);

        String generated = forwarded.getRequest().getHeaders().getFirst(HEADER);
        assertThat(generated).isNotBlank();
        // não lança IllegalArgumentException → formato UUID válido
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    private ServerWebExchange runFilter(MockServerWebExchange exchange) {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(captor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        return captor.getValue();
    }
}
