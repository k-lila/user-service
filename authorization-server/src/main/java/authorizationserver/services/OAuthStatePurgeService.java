package authorizationserver.services;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Purga periódica do estado OAuth expirado na tabela {@code oauth2_authorization} (ADR-022).
 *
 * <p>O {@code JdbcOAuth2AuthorizationService} do SAS grava uma linha por autorização e
 * <b>nunca</b> apaga nenhuma: o access token expira, o refresh token expira, e a linha
 * permanece. É crescimento monotônico de uma linha por login — a tabela vira um log de
 * autenticações que ninguém lê, encarecendo backup, restore e o próprio índice primário.
 *
 * <p><b>Critério de morte.</b> Uma linha só é apagada quando <i>todas</i> as suas colunas de
 * expiração já passaram, com folga de {@code app.oauth-state.purge-grace}. Usar uma coluna só
 * (p. ex. {@code access_token_expires_at}) apagaria autorizações cujo refresh token ainda é
 * válido, deslogando usuários ativos — daí o {@code GREATEST} sobre as seis.
 *
 * <p><b>Fail-closed no lock</b>, como o {@code OutboxRetryService} do user-service e pelo mesmo
 * motivo invertido: aqui o dano de rodar concorrente não é duplicar e-mail, é duas transações
 * disputando as mesmas linhas. Um ciclo pulado não custa nada — o próximo apanha as mesmas
 * linhas, que só ficaram mais expiradas.
 */
@Service
public class OAuthStatePurgeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuthStatePurgeService.class);

    private static final String LOCK_KEY = "oauth_state_purge:lock";

    /**
     * Apaga em lote as autorizações totalmente expiradas.
     *
     * <p>O {@code COALESCE(..., 'epoch')} trata "coluna nula" como "expirou há muito", porque
     * nulo aqui significa que aquele tipo de grant não foi usado nesta autorização — não que ele
     * nunca expira. Isso, porém, tornaria uma linha com <i>todas</i> as colunas nulas elegível,
     * e é exatamente a forma que uma linha recém-inserida poderia assumir num estado
     * intermediário: daí a disjunção {@code IS NOT NULL}, que exige ao menos uma expiração real
     * antes de considerar a linha morta. Sem ela, este DELETE poderia alcançar estado vivo.
     *
     * <p>O {@code IN (SELECT ... LIMIT ?)} limita o tamanho da transação: numa base que acumulou
     * meses de estado, um DELETE único seguraria lock sobre a tabela inteira. O ciclo seguinte
     * continua de onde parou.
     */
    private static final String DELETE_SQL = """
            DELETE FROM oauth2_authorization WHERE id IN (
                SELECT id FROM oauth2_authorization
                WHERE (
                    authorization_code_expires_at IS NOT NULL
                    OR access_token_expires_at IS NOT NULL
                    OR refresh_token_expires_at IS NOT NULL
                    OR oidc_id_token_expires_at IS NOT NULL
                    OR user_code_expires_at IS NOT NULL
                    OR device_code_expires_at IS NOT NULL
                )
                AND GREATEST(
                    COALESCE(authorization_code_expires_at, 'epoch'),
                    COALESCE(access_token_expires_at, 'epoch'),
                    COALESCE(refresh_token_expires_at, 'epoch'),
                    COALESCE(oidc_id_token_expires_at, 'epoch'),
                    COALESCE(user_code_expires_at, 'epoch'),
                    COALESCE(device_code_expires_at, 'epoch')
                ) < ?
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final Duration grace;
    private final Duration lockTtl;
    private final int batchSize;

    public OAuthStatePurgeService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redis,
            @Value("${app.oauth-state.purge-grace:1d}") Duration grace,
            @Value("${app.oauth-state.purge-interval:6h}") Duration interval,
            @Value("${app.oauth-state.purge-batch-size:500}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.grace = grace;
        // O lock tem de cobrir o ciclo inteiro, senão duas instâncias se sobrepõem no fim.
        this.lockTtl = interval.multipliedBy(2);
        this.batchSize = batchSize;
    }

    @Scheduled(
        fixedDelayString = "${app.oauth-state.purge-interval:6h}",
        initialDelayString = "${app.oauth-state.purge-interval:6h}")
    public void purgeExpiredAuthorizations() {
        if (!acquireLock()) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(grace);
            int removed = jdbcTemplate.update(
                    DELETE_SQL, java.sql.Timestamp.from(cutoff), batchSize);
            if (removed > 0) {
                LOGGER.info(
                    "| OAUTH-PURGE | estado expirado removido | linhas: {} | corte: {}",
                    removed, cutoff);
            }
        } catch (RuntimeException e) {
            // Isolamento de falha: manutenção de background nunca derruba o serviço de auth.
            LOGGER.error("| OAUTH-PURGE | falha na purga | {}", e.toString());
        }
    }

    /** Ver a nota de fail-closed na documentação da classe. */
    private boolean acquireLock() {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(LOCK_KEY, Instant.now().toString(), lockTtl));
        } catch (RuntimeException e) {
            LOGGER.warn("| OAUTH-PURGE | purga pulada, lock indisponível | cause: {}", e.getMessage());
            return false;
        }
    }
}
