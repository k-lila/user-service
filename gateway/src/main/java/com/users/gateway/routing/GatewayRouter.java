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

            // Endpoint público pré-sessão (usuário recém-cadastrado, sem cookie/JWT, ADR-015).
            // Rota explícita, precedendo a genérica "user-service", para não herdar dela o
            // tokenRelay()/tier HIGH por-usuário: não há sessão para relayar, e o tier LOW por-IP é
            // o mesmo de /v1/users/register (anti-abuso/enumeração). Já
            // /v1/users/resend-verification é self-service autenticado e cai na rota genérica.
            .route("user-verify-email", route -> route
                .path("/v1/users/verify-email")
                .filters(f -> f.requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterLow);
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

            // Formulário de login do IdP — front-channel, sem sessão do gateway (o request
            // vem do browser no fluxo OAuth2). Tier MED/IP: mesmo racional do oauth e
            // connect-logout (sem sessão → userKeyResolver devolveria "anonymous" para todos).
            // Rate limit protege o auth-server de flood via borda pública (ADR-019).
            .route("auth-login", route -> route
                .path("/login")
                .filters(f -> f.requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterMed);
                    c.setKeyResolver(ipKeyResolver);
                }))
                .uri("lb://authorization-server")
            )

            // CSS do formulário de login do IdP, pedido pelo browser sem sessão. Sem esta rota o
            // path cai no try_files do nginx → index.html → text/html com nosniff → o browser
            // recusa o CSS e o formulário fica sem estilo. Tier LOW/IP (estático público), sem
            // tokenRelay(); está no permitAll() do SecurityConfig (ADR-019).
            .route("auth-default-ui", route -> route
                .path("/default-ui.css")
                .filters(f -> f.requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterLow);
                    c.setKeyResolver(ipKeyResolver);
                }))
                .uri("lb://authorization-server")
            )

            // Front-channel do RP-Initiated Logout (ADR-018). Sob hostname único o
            // authorization-server não é alcançável de fora, mas o oidcLogoutSuccessHandler
            // redireciona o BROWSER ao end_session_endpoint, que precisa existir na origem pública
            // — sem esta rota o logout termina em 404. Tier MED por-IP porque a requisição chega
            // sem a sessão do gateway (o POST /logout acabou de encerrá-la) e o userKeyResolver
            // colapsaria todos em "anonymous". Sem tokenRelay(): o id_token_hint viaja na query.
            .route("connect-logout", route -> route
                .path("/connect/**")
                .filters(f -> f.requestRateLimiter(c -> {
                    c.setRateLimiter(redisRateLimiterMed);
                    c.setKeyResolver(ipKeyResolver);
                }))
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
