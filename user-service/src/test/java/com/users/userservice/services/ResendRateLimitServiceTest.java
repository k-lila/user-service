package com.users.userservice.services;

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
class ResendRateLimitServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private ResendRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new ResendRateLimitService(redis, 3, Duration.ofHours(1));
    }

    @Test
    void isThrottled_falseQuandoNaoHaChave() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        assertFalse(service.isThrottled("fulano@email.com"));
    }

    @Test
    void isThrottled_falseQuandoAbaixoDoLimite() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("2");

        assertFalse(service.isThrottled("fulano@email.com"));
    }

    @Test
    void isThrottled_trueQuandoAtingeOLimite() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3");

        assertTrue(service.isThrottled("fulano@email.com"));
    }

    @Test
    void recordResend_fixaTtlApenasNaPrimeiraChamada() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        service.recordResend("fulano@email.com");

        verify(redis).expire(anyString(), eq(Duration.ofHours(1)));
    }

    @Test
    void recordResend_naoRefixaTtlAPartirDaSegundaChamada() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(2L);

        service.recordResend("fulano@email.com");

        verify(redis, never()).expire(anyString(), any());
    }

    @Test
    void chave_independeDoCaseEEspacosDoEmail() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        service.isThrottled(" Fulano@Email.com ");
        service.isThrottled("fulano@email.com");

        verify(valueOps, times(2)).get(keyCaptor.capture());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));
    }
}
