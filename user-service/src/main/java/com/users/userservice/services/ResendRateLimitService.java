package com.users.userservice.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Limite de reenvio de e-mail de verificação por conta-alvo (ADR-015, fechamento do BLOCK-003/
 * B3) — complementa o rate limit por IP já existente no gateway (tier LOW). Sem este controle,
 * um atacante com IPs rotativos poderia bombardear a caixa de e-mail de uma vítima específica.
 *
 * <p>Modelo: {@code LoginAttemptService} do authorization-server (janela fixa, {@code INCR}
 * atômico no Redis, TTL fixado só na 1ª ocorrência). Chave é o SHA-256 do e-mail normalizado —
 * não guarda PII em claro.
 */
@Service
public class ResendRateLimitService {

    private static final String KEY_PREFIX = "resend_verification:";

    private final StringRedisTemplate redis;
    private final int maxResendsPerWindow;
    private final Duration window;

    public ResendRateLimitService(
            StringRedisTemplate redis,
            @Value("${app.verification.resend-max-per-window:3}") int maxResendsPerWindow,
            @Value("${app.verification.resend-window:1h}") Duration window) {
        this.redis = redis;
        this.maxResendsPerWindow = maxResendsPerWindow;
        this.window = window;
    }

    /**
     * True se a conta já atingiu o limite de reenvios na janela vigente. Não incrementa —
     * use junto de {@link #recordResend(String)} no chamador, depois de decidir disparar.
     */
    public boolean isThrottled(String email) {
        String value = redis.opsForValue().get(key(email));
        if (value == null) {
            return false;
        }
        try {
            return Integer.parseInt(value) >= maxResendsPerWindow;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Registra uma tentativa de reenvio; fixa o TTL apenas na 1ª (janela fixa). */
    public void recordResend(String email) {
        String key = key(email);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
    }

    private String key(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return KEY_PREFIX + sha256(normalized);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é garantido pela plataforma Java; não deve ocorrer.
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
