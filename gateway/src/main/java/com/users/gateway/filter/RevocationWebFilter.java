package com.users.gateway.filter;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;

import reactor.core.publisher.Mono;

/**
 * Revogação ativa de token na borda (ADR-017) — defesa em profundidade. Como o BFF autentica por
 * sessão (não por bearer header), a checagem do resource server não é exercida pelo tráfego do SPA;
 * este {@link GlobalFilter} inspeciona o access token guardado na sessão (o JWT que seria relayado),
 * lê o epoch de revogação por usuário no Redis ({@code revoke:user:{userID}}, mesmo que o
 * user-service grava) e, se o token foi emitido antes da revogação, responde <b>401</b> e invalida a
 * sessão — forçando re-login. O user-service permanece a camada autoritativa (recursos sensíveis
 * vivem lá); aqui o ganho é rejeição imediata na borda sem o salto downstream.
 *
 * <p><b>Fail-open</b>: qualquer erro (decodificação, Redis) deixa a request seguir — um outage não
 * derruba a autenticação. Atua só sobre {@link OAuth2AuthenticationToken} (login por sessão);
 * tráfego não autenticado ou por bearer passa direto.
 */
@Component
public class RevocationWebFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(RevocationWebFilter.class);

    // Id do registro do cliente OAuth2 do BFF (chave em spring.security.oauth2.client.registration.*).
    private static final String CLIENT_REGISTRATION_ID = "gateway-client";

    private final ServerOAuth2AuthorizedClientRepository authorizedClientRepository;
    private final ReactiveJwtDecoder jwtDecoder;
    private final ReactiveStringRedisTemplate redis;
    private final boolean enabled;
    private final String keyPrefix;

    public RevocationWebFilter(
            ServerOAuth2AuthorizedClientRepository authorizedClientRepository,
            ReactiveJwtDecoder jwtDecoder,
            ReactiveStringRedisTemplate redis,
            @Value("${security.revocation.enabled:true}") boolean enabled,
            @Value("${security.revocation.key-prefix:revoke:user:}") String keyPrefix) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.jwtDecoder = jwtDecoder;
        this.redis = redis;
        this.enabled = enabled;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(OAuth2AuthenticationToken.class::isInstance)
                .flatMap(auth -> authorizedClientRepository
                        .<OAuth2AuthorizedClient>loadAuthorizedClient(CLIENT_REGISTRATION_ID, auth, exchange))
                .flatMap(this::isRevoked)
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    LOGGER.warn("| revogação | falha na checagem de borda (fail-open) | {}", e.toString());
                    return Mono.just(false);
                })
                .flatMap(revoked -> revoked ? deny(exchange) : chain.filter(exchange));
    }

    private Mono<Boolean> isRevoked(OAuth2AuthorizedClient client) {
        String tokenValue = client.getAccessToken().getTokenValue();
        return jwtDecoder.decode(tokenValue).flatMap(jwt -> {
            String userID = jwt.getClaimAsString("userID");
            Instant issuedAt = jwt.getIssuedAt();
            if (userID == null || issuedAt == null) {
                return Mono.just(false);
            }
            return redis.opsForValue().get(keyPrefix + userID)
                    .map(value -> isBefore(issuedAt, value))
                    .defaultIfEmpty(false);
        });
    }

    private boolean isBefore(Instant issuedAt, String revokeEpochMillis) {
        try {
            return issuedAt.toEpochMilli() < Long.parseLong(revokeEpochMillis);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Mono<Void> deny(ServerWebExchange exchange) {
        LOGGER.info("| revogação | token revogado | rejeitado na borda | 401");
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getSession()
                .flatMap(WebSession::invalidate)
                .then(Mono.defer(() -> exchange.getResponse().setComplete()));
    }

    @Override
    public int getOrder() {
        // Cedo entre os global filters: barra o token revogado antes do relay ao backend.
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
