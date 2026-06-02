package com.users.gateway.filter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class JwtHeaderPropagationFilter implements GlobalFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtHeaderPropagationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
            .cast(JwtAuthenticationToken.class)
            .flatMap(auth -> {
                Jwt jwt = auth.getToken();
                String userId = jwt.getSubject();
                String email = jwt.getClaim("email");
                List<String> roles = jwt.getClaim("roles");
                ServerHttpRequest request = exchange.getRequest()
                    .mutate()
                    .header("X-User-Id", userId)
                    .header("X-User-Email", email)
                    .header("X-User-Roles", String.join(",", roles))
                    .build();
                return chain.filter(
                    exchange.mutate().request(request).build()
                );
            })
            .switchIfEmpty(Mono.defer(() -> {
                LOGGER.debug("| sem JWT | propagação de headers ignorada");
                return chain.filter(exchange);
            }));
    }
}