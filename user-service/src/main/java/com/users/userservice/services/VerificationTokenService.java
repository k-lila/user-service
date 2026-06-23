package com.users.userservice.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Gera o token opaco (não JWT) de verificação de e-mail e calcula seu hash SHA-256 — só o
 * hash é persistido no Mongo (ADR-015); o token em claro é devolvido uma única vez, para
 * compor o link de verificação enviado por e-mail.
 */
@Service
public class VerificationTokenService {

    private static final int TOKEN_BYTES = 32; // 256 bits de entropia
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration tokenTtl;

    public VerificationTokenService(@Value("${app.verification.token-ttl:15m}") Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String generateToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é garantido pela JVM (algoritmo padrão) — nunca deveria ocorrer.
            throw new IllegalStateException("Algoritmo SHA-256 indisponível", e);
        }
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }
}
