package authorizationserver.services;

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
 * Lockout anti-brute-force por par (conta, IP), com estado no Redis (atômico via
 * {@code INCR}, seguro entre instâncias — Sentinel compartilhado).
 *
 * <p>Janela fixa: o TTL é fixado na 1ª falha; após {@code maxAttempts} falhas o par
 * fica bloqueado pelo restante da janela e expira sozinho. A chave é o SHA-256 de
 * {@code emailLower|ip} — não guarda PII em claro e normaliza o case do e-mail
 * (sem isso, variar maiúsculas contornaria o lockout).
 */
@Service
public class LoginAttemptService {

    private static final String KEY_PREFIX = "login_fail:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration lockoutDuration;

    public LoginAttemptService(
            StringRedisTemplate redis,
            @Value("${security.lockout.max-attempts:5}") int maxAttempts,
            @Value("${security.lockout.duration:15m}") Duration lockoutDuration) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = lockoutDuration;
    }

    /** Registra uma falha de senha; fixa o TTL apenas na 1ª (janela fixa). */
    public void recordFailure(String email, String ip) {
        String key = key(email, ip);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, lockoutDuration);
        }
    }

    /** True se o par (email, ip) atingiu o limite dentro da janela vigente. */
    public boolean isBlocked(String email, String ip) {
        String value = redis.opsForValue().get(key(email, ip));
        if (value == null) {
            return false;
        }
        try {
            return Integer.parseInt(value) >= maxAttempts;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Login bem-sucedido: zera o contador do par (email, ip). */
    public void loginSucceeded(String email, String ip) {
        redis.delete(key(email, ip));
    }

    private String key(String email, String ip) {
        String normalized = email.trim().toLowerCase(Locale.ROOT) + "|" + ip;
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
