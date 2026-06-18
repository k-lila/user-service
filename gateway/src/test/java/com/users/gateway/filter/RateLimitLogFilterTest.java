package com.users.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unitários do {@link RateLimitLogFilter}: loga WARN apenas em respostas 429, ignora os
 * demais status e executa com precedência máxima.
 */
class RateLimitLogFilterTest {

    private final RateLimitLogFilter filter = new RateLimitLogFilter("CF-Connecting-IP");

    @Test
    void deveTerPrecedenciaMaxima() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void deveLogarWarnComRemoteIp_quandoStatus429() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/users/register")
                        .remoteAddress(new InetSocketAddress("203.0.113.9", 9000)));
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        GatewayFilterChain chain = e -> Mono.empty();

        ListAppender<ILoggingEvent> appender = attachAppender();
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(appender.list).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage()).contains("429").contains("203.0.113.9");
        });
    }

    @Test
    void deveLogarCfConnectingIp_quandoStatus429ComHeaderConfiavel() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/users/register")
                        .header("CF-Connecting-IP", "203.0.113.42")
                        .remoteAddress(new InetSocketAddress("10.0.0.1", 9000)));
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        GatewayFilterChain chain = e -> Mono.empty();

        ListAppender<ILoggingEvent> appender = attachAppender();
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(appender.list).anySatisfy(ev ->
                assertThat(ev.getFormattedMessage()).contains("203.0.113.42").doesNotContain("10.0.0.1"));
    }

    @Test
    void deveLogarRemoteUnknown_quandoStatus429SemRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/users/register"));
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        GatewayFilterChain chain = e -> Mono.empty();

        ListAppender<ILoggingEvent> appender = attachAppender();
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(appender.list).anySatisfy(ev ->
                assertThat(ev.getFormattedMessage()).contains("remote: unknown"));
    }

    @Test
    void naoDeveLogar_quandoStatusDiferenteDe429() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/users/me"));
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        GatewayFilterChain chain = e -> Mono.empty();

        ListAppender<ILoggingEvent> appender = attachAppender();
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(appender.list).isEmpty();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
