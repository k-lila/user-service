package com.users.userservice.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.OptionalLong;

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
class TokenRevocationServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private static final String PREFIX = "revoke:user:";
    private static final Duration TTL = Duration.ofMinutes(75);

    private TokenRevocationService service(boolean enabled) {
        return new TokenRevocationService(redis, enabled, PREFIX, TTL);
    }

    @Test
    void revoke_deveGravarEpochAtualComTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);

        service(true).revoke("user-1");

        verify(valueOps).set(eq(PREFIX + "user-1"), any(String.class), eq(TTL));
    }

    @Test
    void revoke_deveSerNoOp_quandoDesabilitado() {
        service(false).revoke("user-1");
        verify(redis, never()).opsForValue();
    }

    @Test
    void revoke_deveSerNoOp_quandoUserIdVazio() {
        service(true).revoke("  ");
        verify(redis, never()).opsForValue();
    }

    @Test
    void revoke_naoDevePropagarExcecao_quandoRedisFalha() {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down")).when(valueOps).set(any(), any(), any(Duration.class));

        // Fail-open: a operação de negócio que disparou a revogação não pode cair.
        assertThatCode(() -> service(true).revoke("user-1")).doesNotThrowAnyException();
    }

    @Test
    void revokedAtMillis_deveRetornarEpoch_quandoChavePresente() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(PREFIX + "user-1")).thenReturn("1700000000000");

        assertThat(service(true).revokedAtMillis("user-1")).isEqualTo(OptionalLong.of(1700000000000L));
    }

    @Test
    void revokedAtMillis_deveRetornarVazio_quandoChaveAusente() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(PREFIX + "user-1")).thenReturn(null);

        assertThat(service(true).revokedAtMillis("user-1")).isEmpty();
    }

    @Test
    void revokedAtMillis_deveRetornarVazio_quandoDesabilitado() {
        assertThat(service(false).revokedAtMillis("user-1")).isEmpty();
        verify(redis, never()).opsForValue();
    }

    @Test
    void revokedAtMillis_deveRetornarVazio_quandoRedisFalha() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenThrow(new RuntimeException("redis down"));

        // Fail-open na leitura: erro de Redis é tratado como "sem revogação".
        assertThat(service(true).revokedAtMillis("user-1")).isEmpty();
    }
}
