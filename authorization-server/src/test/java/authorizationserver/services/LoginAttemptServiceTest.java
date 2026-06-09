package authorizationserver.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(redis, 5, Duration.ofMinutes(15));
    }

    @Test
    void recordFailure_fixaTtlApenasNaPrimeiraFalha() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        service.recordFailure("fulano@email.com", "10.0.0.1");

        verify(redis).expire(anyString(), eq(Duration.ofMinutes(15)));
    }

    @Test
    void recordFailure_naoRefixaTtlAPartirDaSegundaFalha() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(2L);

        service.recordFailure("fulano@email.com", "10.0.0.1");

        verify(redis, never()).expire(anyString(), any());
    }

    @Test
    void isBlocked_trueQuandoContadorAtingeOLimite() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("5");

        assertTrue(service.isBlocked("fulano@email.com", "10.0.0.1"));
    }

    @Test
    void isBlocked_falseQuandoAbaixoDoLimite() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("4");

        assertFalse(service.isBlocked("fulano@email.com", "10.0.0.1"));
    }

    @Test
    void isBlocked_falseQuandoSemContador() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        assertFalse(service.isBlocked("fulano@email.com", "10.0.0.1"));
    }

    @Test
    void loginSucceeded_removeOContador() {
        service.loginSucceeded("fulano@email.com", "10.0.0.1");

        verify(redis).delete(anyString());
    }

    @Test
    void chave_independeDoCaseDoEmail() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("5");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        service.isBlocked("Fulano@Email.com", "10.0.0.1");
        service.isBlocked("fulano@email.com", "10.0.0.1");

        verify(valueOps, times(2)).get(keyCaptor.capture());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));
    }
}
