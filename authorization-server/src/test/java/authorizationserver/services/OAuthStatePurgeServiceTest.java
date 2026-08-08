package authorizationserver.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthStatePurgeServiceTest {

    private static final Duration GRACE = Duration.ofDays(1);
    private static final Duration INTERVAL = Duration.ofHours(6);
    private static final int BATCH = 500;

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private OAuthStatePurgeService service;

    @BeforeEach
    void setUp() {
        service = new OAuthStatePurgeService(jdbcTemplate, redis, GRACE, INTERVAL, BATCH);
        when(redis.opsForValue()).thenReturn(valueOps);
        lockAcquired(true);
    }

    private void lockAcquired(boolean acquired) {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(acquired);
    }

    @Test
    void deveApagarComCorteRecuadoPelaCarencia_eLimitadoPeloLote() {
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(3);
        Instant antes = Instant.now();

        service.purgeExpiredAuthorizations();

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), args.capture(), eq(BATCH));

        // A carência recua o corte: uma autorização que expirou agora sobrevive a este ciclo.
        Instant corte = ((Timestamp) args.getValue()).toInstant();
        assertThat(corte).isBefore(antes.minus(GRACE).plusSeconds(5));
        assertThat(corte).isAfter(antes.minus(GRACE).minusSeconds(5));
    }

    /**
     * O predicado tem de exigir que <b>todas</b> as expirações tenham passado. Filtrar por uma
     * coluna só (o access token, a mais óbvia) apagaria autorizações cujo refresh token ainda é
     * válido — deslogando usuários ativos a cada ciclo de purga.
     */
    @Test
    void deveExigirQueTodasAsColunasDeExpiracaoTenhamPassado() {
        service.purgeExpiredAuthorizations();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(), eq(BATCH));

        String comando = sql.getValue();
        assertThat(comando).contains("GREATEST");
        assertThat(comando).contains("refresh_token_expires_at");
        assertThat(comando).contains("access_token_expires_at");
        assertThat(comando).contains("authorization_code_expires_at");
        // Guarda contra linha com todas as colunas nulas, que o COALESCE para 'epoch' tornaria
        // elegível — o DELETE alcançaria estado ainda vivo.
        assertThat(comando).contains("IS NOT NULL");
    }

    @Test
    void naoDevePurgar_quandoOutraInstanciaJaSeguraOLock() {
        lockAcquired(false);

        service.purgeExpiredAuthorizations();

        verifyNoInteractions(jdbcTemplate);
    }

    /**
     * Fail-closed no lock, como o {@code OutboxRetryService} do user-service. Sem lock confirmado
     * não roda: o custo de pular um ciclo é nulo (as linhas só ficaram mais expiradas), enquanto
     * duas instâncias purgando juntas disputam as mesmas linhas.
     */
    @Test
    void naoDevePurgar_quandoRedisIndisponivel() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis fora"));

        service.purgeExpiredAuthorizations();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void naoDevePropagarExcecao_quandoOBancoFalha() {
        when(jdbcTemplate.update(anyString(), any(), any()))
                .thenThrow(new RuntimeException("postgres fora"));

        // Manutenção de background nunca derruba o serviço de autenticação.
        assertThatCode(() -> service.purgeExpiredAuthorizations()).doesNotThrowAnyException();
    }

    @Test
    void deveDerivarTtlDoLockDoIntervaloDeVarredura() {
        service.purgeExpiredAuthorizations();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).setIfAbsent(anyString(), anyString(), ttl.capture());

        // O lock tem de cobrir o ciclo inteiro, senão duas instâncias se sobrepõem no fim dele.
        assertThat(ttl.getValue()).isGreaterThan(INTERVAL);
    }

    @Test
    void deveRespeitarOTetoDeLotePorCiclo() {
        OAuthStatePurgeService comLotePequeno =
                new OAuthStatePurgeService(jdbcTemplate, redis, GRACE, INTERVAL, 10);

        comLotePequeno.purgeExpiredAuthorizations();

        verify(jdbcTemplate).update(anyString(), any(), eq(10));
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(anyString(), any(), eq(BATCH));
    }

    @Test
    void deveManterOLimitDentroDoSql_paraNaoSegurarLockSobreATabelaInteira() {
        service.purgeExpiredAuthorizations();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(), eq(BATCH));

        assertThat(sql.getValue()).contains("LIMIT ?");
    }
}
