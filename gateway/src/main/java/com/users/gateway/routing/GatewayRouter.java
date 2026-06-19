package com.users.gateway.routing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class GatewayRouter {

	@Bean
	public RouteLocator routes(RouteLocatorBuilder builder,
                                @Qualifier("redisRateLimiterLow") RedisRateLimiter redisRateLimiterLow,
                                @Qualifier("redisRateLimiterMed") RedisRateLimiter redisRateLimiterMed,
                                @Qualifier("redisRateLimiterHigh") RedisRateLimiter redisRateLimiterHigh,
                                @Qualifier("ipKeyResolver") KeyResolver ipKeyResolver,
                                @Qualifier("userKeyResolver") KeyResolver userKeyResolver
                            ) {
		return builder.routes()

            .route("user-register", route -> route
                .path("/v1/users/register")
                .filters(f -> f.requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterLow); // mais restritivo
                    c.setKeyResolver(ipKeyResolver);
                }))
                .uri("lb://user-service")
            )

            .route("oauth", route -> route
                .path("/oauth2/**")
                .filters(f -> f.requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterMed); // restritivo
                    c.setKeyResolver(ipKeyResolver);
                }))
                .uri("lb://authorization-server")
            )

            .route("auth-login", route -> route
                .path("/login")
                .uri("lb://authorization-server")
            )
        
            .route("user-service", route -> route
                .path("/v1/users/**")
                // tokenRelay() injeta o access token da sessão BFF como Authorization: Bearer.
                // Declarado aqui (e não via default-filters) porque rotas do RouteLocatorBuilder
                // não recebem os default-filters do gateway.yml.
                .filters(f -> f.tokenRelay().requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterHigh); //menos restritivo
                    c.setKeyResolver(userKeyResolver);
                }))
                .uri("lb://user-service")
            )

            .route("admin-service", route -> route
                .path("/v1/admin/**")
                // Rate limit MED (não HIGH): superfície sensível (ADR-014), poucos operadores,
                // reduz blast radius em caso de token ADMIN comprometido. ROLE_ADMIN é checado
                // exclusivamente no AdminController (user-service) via @PreAuthorize — o
                // gateway não ganha hasRole() aqui (decisão explícita, AC-13).
                .filters(f -> f.tokenRelay().requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterMed);
                    c.setKeyResolver(userKeyResolver);
                }))
                .uri("lb://user-service")
            )

            // ##### swagger
            .route("user-service-docs", r -> r
            .path("/v3/api-docs/user/**", "/v3/api-docs/user/")
            .filters(f -> f.rewritePath(
                "/v3/api-docs/user(?<segment>/?.*)",
                "/v3/api-docs${segment}"
            ))
            .uri("lb://user-service")
            )

            .route("authorization-server-docs", route -> route
            .path("/v3/api-docs/authorization-server/**", "/v3/api-docs/authorization-server/")
            .filters(f -> f.rewritePath(
                "/v3/api-docs/authorization-server(?<segment>/?.*)",
                "/v3/api-docs${segment}"
            ))
            .uri("lb://authorization-server")
            )

            .build();
    }

}
