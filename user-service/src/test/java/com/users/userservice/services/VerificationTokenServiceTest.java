package com.users.userservice.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerificationTokenServiceTest {

    private VerificationTokenService service;

    @BeforeEach
    void setUp() {
        service = new VerificationTokenService(Duration.ofMinutes(15));
    }

    @Test
    void deveGerarTokensDistintos_emChamadasSucessivas() {
        String token1 = service.generateToken();
        String token2 = service.generateToken();

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    void deveGerarTokenNaoVazio() {
        String token = service.generateToken();

        assertThat(token).isNotBlank();
    }

    @Test
    void deveCalcularHashDeterministico_paraOMesmoToken() {
        String token = service.generateToken();

        String hash1 = service.hashToken(token);
        String hash2 = service.hashToken(token);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void deveCalcularHashesDiferentes_paraTokensDiferentes() {
        String tokenA = service.generateToken();
        String tokenB = service.generateToken();

        String hashA = service.hashToken(tokenA);
        String hashB = service.hashToken(tokenB);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void hashTokenNuncaDeveRetornarOTokenEmClaro() {
        String token = service.generateToken();

        String hash = service.hashToken(token);

        assertThat(hash).isNotEqualTo(token);
    }

    @Test
    void deveRefletirTtlInjetado() {
        VerificationTokenService customService = new VerificationTokenService(Duration.ofMinutes(30));

        assertThat(customService.getTokenTtl()).isEqualTo(Duration.ofMinutes(30));
    }
}
