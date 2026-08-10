package authorizationserver.services;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Leitura do "epoch de revogação" por usuário (ADR-017): o mesmo valor que o user-service grava no
 * Redis (chave {@code revoke:user:{userID}}), respondendo "o titular foi revogado DEPOIS deste
 * instante?".
 *
 * <p><b>Dois consumidores, um método.</b> A pergunta é genérica e {@link #isRevoked} sempre foi — o
 * que era específico de refresh era só este javadoc:
 * <ol>
 *   <li><b>Grant {@code refresh_token}</b> ({@code TokenCustomizerConfig}, ADR-017): barra a
 *       reemissão quando a revogação é mais recente que a emissão do refresh token apresentado. Sem
 *       isso, o gateway (BFF) renovaria o access token silenciosamente e reemitiria credenciais
 *       válidas indefinidamente, derrotando a checagem dos resource servers.</li>
 *   <li><b>Caminho degradado da re-derivação na emissão</b>
 *       ({@code AuthorizationEndpointRevalidationFilter}, ADR-025): quando o user-service está
 *       indisponível, a fonte de verdade não pode ser consultada e esta é a única checagem que
 *       precisa apenas do Redis. O instante comparado é o <b>instante de autenticação da sessão</b>
 *       — que a re-derivação nunca re-carimba, sob pena de {@code epoch > instante} nunca mais ser
 *       verdadeiro e o caminho degradado virar decorativo.</li>
 * </ol>
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
     * {@code true} se o titular foi revogado depois de {@code credentialIssuedAt} — a credencial
     * apresentada precede a revogação e deve ser barrada. {@code false} quando desabilitado, sem
     * dados suficientes, sem marca de revogação, ou em erro de Redis (fail-open).
     *
     * @param credentialIssuedAt emissão do refresh token (ADR-017) ou instante de autenticação da
     *                           sessão do IdP (ADR-025, caminho degradado)
     */
    public boolean isRevoked(String userID, Instant credentialIssuedAt) {
        if (!enabled || userID == null || userID.isBlank() || credentialIssuedAt == null) {
            return false;
        }
        try {
            String value = redis.opsForValue().get(keyPrefix + userID);
            if (value == null) {
                return false;
            }
            return Long.parseLong(value) > credentialIssuedAt.toEpochMilli();
        } catch (RuntimeException e) {
            LOGGER.warn("| revogação | falha ao ler epoch (fail-open) | ID: {} | {}", userID, e.toString());
            return false;
        }
    }
}
