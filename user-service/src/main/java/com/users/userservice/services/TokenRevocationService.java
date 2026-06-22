package com.users.userservice.services;

import java.time.Duration;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Revogação ativa de token por usuário (fecha o gap "Ausência de revogação ativa de token",
 * ADR-017). Mantém no Redis um "epoch de revogação" por titular — o instante (millis) a partir do
 * qual todo access token emitido <b>antes</b> dele deve ser rejeitado. É escrito em cada evento que
 * deveria invalidar sessões vivas (revogação de role, desativação, hard-delete), junto das evictions
 * de cache já existentes nesses pontos.
 *
 * <p>O validador dos resource servers ({@code RevocationTokenValidator}) e o guard de refresh do
 * authorization-server comparam o {@code iat} do token a este epoch. A chave expira sozinha após
 * {@code security.revocation.ttl} (≥ a vida máxima de um refresh token) — passado esse tempo não há
 * token vivo anterior à revogação, então a marca é descartável.
 *
 * <p><b>Fail-open</b> (decisão consciente — disponibilidade sobre rigor): erro de Redis na escrita
 * loga WARN sem derrubar a operação de negócio; na leitura, retorna vazio (tratado como "sem
 * revogação"). Um outage de Redis não bloqueia a autenticação.
 */
@Service
public class TokenRevocationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenRevocationService.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String keyPrefix;
    private final Duration ttl;

    public TokenRevocationService(
            StringRedisTemplate redis,
            @Value("${security.revocation.enabled:true}") boolean enabled,
            @Value("${security.revocation.key-prefix:revoke:user:}") String keyPrefix,
            @Value("${security.revocation.ttl:75m}") Duration ttl) {
        this.redis = redis;
        this.enabled = enabled;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    /**
     * Marca todos os tokens do titular emitidos até agora como revogados. Idempotente: grava sempre
     * o instante atual (a revogação mais recente vence). Isolada de falha (ver classe). No-op quando
     * {@code security.revocation.enabled=false}.
     */
    public void revoke(String userID) {
        if (!enabled || userID == null || userID.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(keyPrefix + userID, Long.toString(System.currentTimeMillis()), ttl);
            LOGGER.info("| revogação | token revogado | ID: {}", userID);
        } catch (RuntimeException e) {
            LOGGER.warn("| revogação | falha ao gravar epoch no Redis (fail-open) | ID: {} | {}", userID, e.toString());
        }
    }

    /**
     * Epoch de revogação (millis) do titular; vazio se não houver marca ou em erro de Redis
     * (fail-open). O chamador trata "vazio" como token não-revogado.
     */
    public OptionalLong revokedAtMillis(String userID) {
        if (!enabled || userID == null || userID.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            String value = redis.opsForValue().get(keyPrefix + userID);
            return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
        } catch (RuntimeException e) {
            LOGGER.warn("| revogação | falha ao ler epoch (fail-open) | ID: {} | {}", userID, e.toString());
            return OptionalLong.empty();
        }
    }
}
