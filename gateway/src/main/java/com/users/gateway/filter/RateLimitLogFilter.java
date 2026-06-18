package com.users.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.users.gateway.util.ClientIpResolver;

import reactor.core.publisher.Mono;

/**
 * Loga rejeições por rate limit (HTTP 429). O {@code RedisRateLimiter} apenas
 * seta o status da resposta e não registra log próprio — este filtro envolve a
 * cadeia e, ao final, registra quando a requisição foi barrada.
 */
@Component
public class RateLimitLogFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitLogFilter.class);

    private final String trustedClientIpHeader;

    public RateLimitLogFilter(
            @Value("${security.trusted-client-ip-header:CF-Connecting-IP}") String trustedClientIpHeader) {
        this.trustedClientIpHeader = trustedClientIpHeader;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            if (status != null && status.value() == 429) {
                ServerHttpRequest request = exchange.getRequest();
                // Mesma resolução do ipKeyResolver (CF-Connecting-IP → remoteAddress),
                // para o log de rejeição bater com a chave que de fato particionou o limite.
                String remote = ClientIpResolver.resolve(request, trustedClientIpHeader);
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
