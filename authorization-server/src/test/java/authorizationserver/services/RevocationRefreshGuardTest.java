package authorizationserver.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevocationRefreshGuardTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private static final String PREFIX = "revoke:user:";

    private RevocationRefreshGuard guard(boolean enabled) {
        return new RevocationRefreshGuard(redis, enabled, PREFIX);
    }

    @Test
    void deveBarrar_quandoRevogacaoMaisRecenteQueORefreshToken() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(PREFIX + "u1")).thenReturn("2000");

        assertThat(guard(true).isRevoked("u1", Instant.ofEpochMilli(1000))).isTrue();
    }

    @Test
    void naoDeveBarrar_quandoRefreshEmitidoAposARevogacao() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(PREFIX + "u1")).thenReturn("1000");

        assertThat(guard(true).isRevoked("u1", Instant.ofEpochMilli(2000))).isFalse();
    }

    @Test
    void naoDeveBarrar_quandoSemMarcaDeRevogacao() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(PREFIX + "u1")).thenReturn(null);

        assertThat(guard(true).isRevoked("u1", Instant.ofEpochMilli(1000))).isFalse();
    }

    @Test
    void naoDeveBarrar_quandoDesabilitado() {
        assertThat(guard(false).isRevoked("u1", Instant.ofEpochMilli(1000))).isFalse();
        verify(redis, never()).opsForValue();
    }

    @Test
    void naoDeveBarrar_quandoIssuedAtNulo() {
        assertThat(guard(true).isRevoked("u1", null)).isFalse();
        verify(redis, never()).opsForValue();
    }

    @Test
    void naoDeveBarrar_quandoRedisFalha() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenThrow(new RuntimeException("redis down"));

        // Fail-open: erro de Redis não barra o refresh.
        assertThat(guard(true).isRevoked("u1", Instant.ofEpochMilli(1000))).isFalse();
    }
}
