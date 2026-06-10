package com.users.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Loga rejeições por rate limit (HTTP 429). O {@code RedisRateLimiter} apenas
 * seta o status da resposta e não registra log próprio — este filtro envolve a
 * cadeia e, ao final, registra quando a requisição foi barrada.
 */
@Component
public class RateLimitLogFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            if (status != null && status.value() == 429) {
                ServerHttpRequest request = exchange.getRequest();
                // getHostString(): com server.forward-headers-strategy=framework o remoteAddress
                // vem do X-Forwarded-For como InetSocketAddress unresolved (getAddress() == null).
                String remote = request.getRemoteAddress() != null
                    ? request.getRemoteAddress().getHostString()
                    : "unknown";
                LOGGER.warn(
                    "| 429 | rate limit excedido | {} {} | remote: {}",
                    request.getMethod(),
                    request.getPath().value(),
                    remote
                );
            }
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
