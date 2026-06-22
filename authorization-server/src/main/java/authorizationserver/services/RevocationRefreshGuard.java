package authorizationserver.services;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Fechamento do caminho do refresh para a revogação ativa de token (ADR-017). Lê o mesmo "epoch de
 * revogação" por usuário que o user-service grava no Redis (chave {@code revoke:user:{userID}}) e
 * decide se um grant {@code refresh_token} deve ser barrado: barra quando a revogação é mais recente
 * que a emissão do refresh token apresentado — i.e., o usuário foi revogado/desativado depois daquele
 * login. Sem isso, o gateway (BFF) renovaria o access token silenciosamente e reemitiria credenciais
 * válidas indefinidamente, derrotando a checagem dos resource servers.
 *
 * <p><b>Fail-open</b> (simétrico ao {@code TokenRevocationService} do user-service): erro de Redis →
 * {@code false} (não barra), priorizando disponibilidade sobre rigor. O {@code key-prefix} precisa
 * casar com o do user-service (default {@code revoke:user:}).
 */
@Service
public class RevocationRefreshGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(RevocationRefreshGuard.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String keyPrefix;

    // Só lê o epoch (a escrita/TTL vivem no user-service) — não precisa de security.revocation.ttl.
    public RevocationRefreshGuard(
            StringRedisTemplate redis,
            @Value("${security.revocation.enabled:true}") boolean enabled,
            @Value("${security.revocation.key-prefix:revoke:user:}") String keyPrefix) {
        this.redis = redis;
        this.enabled = enabled;
        this.keyPrefix = keyPrefix;
    }

    /**
     * {@code true} se o titular foi revogado depois de {@code refreshTokenIssuedAt} — o refresh deve
     * ser barrado. {@code false} quando desabilitado, sem dados suficientes, sem marca de revogação,
     * ou em erro de Redis (fail-open).
     */
    public boolean isRevoked(String userID, Instant refreshTokenIssuedAt) {
        if (!enabled || userID == null || userID.isBlank() || refreshTokenIssuedAt == null) {
            return false;
        }
        try {
            String value = redis.opsForValue().get(keyPrefix + userID);
            if (value == null) {
                return false;
            }
            return Long.parseLong(value) > refreshTokenIssuedAt.toEpochMilli();
        } catch (RuntimeException e) {
            LOGGER.warn("| revogação | falha ao ler epoch no refresh (fail-open) | ID: {} | {}", userID, e.toString());
            return false;
        }
    }
}
