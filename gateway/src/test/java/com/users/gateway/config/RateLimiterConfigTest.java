package com.users.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unitários do {@link RateLimiterConfig}: valores dos três limiters e a lógica de
 * resolução de chave (IP via X-Forwarded-For/remoteAddress; usuário via principal).
 */
class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    // ----- RedisRateLimiter: replenishRate / burstCapacity -----

    @Test
    void deveConfigurarLimiterHigh_com10Replenish20Burst() {
        assertLimiter(config.redisRateLimiterHigh(), 10, 20);
    }

    @Test
    void deveConfigurarLimiterMed_com5Replenish10Burst() {
        assertLimiter(config.redisRateLimiterMed(), 5, 10);
    }

    @Test
    void deveConfigurarLimiterLow_com2Replenish5Burst() {
        assertLimiter(config.redisRateLimiterLow(), 2, 5);
    }

    private void assertLimiter(RedisRateLimiter limiter, int replenish, long burst) {
        RedisRateLimiter.Config def = (RedisRateLimiter.Config)
                ReflectionTestUtils.invokeMethod(limiter, "getDefaultConfig");
        assertThat(def).isNotNull();
        assertThat(def.getReplenishRate()).isEqualTo(replenish);
        assertThat(def.getBurstCapacity()).isEqualTo(burst);
    }

    // ----- ipKeyResolver -----

    @Test
    void deveUsarPrimeiroIpDoXForwardedFor_quandoHeaderPresente() {
        KeyResolver resolver = config.ipKeyResolver();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").header("X-Forwarded-For", "203.0.113.5, 10.0.0.1"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("203.0.113.5")
                .verifyComplete();
    }

    @Test
    void deveUsarHostStringDoRemoteAddress_quandoSemXForwardedFor() {
        KeyResolver resolver = config.ipKeyResolver();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .remoteAddress(new InetSocketAddress("198.51.100.7", 12345)));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("198.51.100.7")
                .verifyComplete();
    }

    @Test
    void deveRetornarUnknown_quandoSemHeaderESemRemoteAddress() {
        KeyResolver resolver = config.ipKeyResolver();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("unknown")
                .verifyComplete();
    }

    // ----- userKeyResolver -----

    @Test
    void deveResolverNomeDoPrincipal_quandoAutenticado() {
        KeyResolver resolver = config.userKeyResolver();
        Principal principal = () -> "user@test.com";
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
                .mutate().principal(Mono.just(principal)).build();

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("user@test.com")
                .verifyComplete();
    }

    @Test
    void deveResolverAnonymous_quandoSemPrincipal() {
        KeyResolver resolver = config.userKeyResolver();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("anonymous")
                .verifyComplete();
    }
}
