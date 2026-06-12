package com.users.userservice.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LogUtilsTest {

    @Test
    void deveMascararParaTresEstrelas_quandoNulo() {
        assertEquals("***", LogUtils.maskEmail(null));
    }

    @Test
    void deveMascararParaTresEstrelas_quandoVazio() {
        assertEquals("***", LogUtils.maskEmail(""));
    }

    @Test
    void deveMascararParaTresEstrelas_quandoEmBranco() {
        assertEquals("***", LogUtils.maskEmail("   "));
    }

    @Test
    void deveMascararParaTresEstrelas_quandoSemArroba() {
        assertEquals("***", LogUtils.maskEmail("semarroba"));
    }

    @Test
    void deveMascararParaTresEstrelas_quandoArrobaNaPrimeiraPosicao() {
        assertEquals("***", LogUtils.maskEmail("@dominio.com"));
    }

    @Test
    void deveMascararMantendoPrimeiraLetra_quandoLocalDeUmCaractere() {
        assertEquals("a***@e.com", LogUtils.maskEmail("a@e.com"));
    }

    @Test
    void deveMascararMantendoPrimeiraLetraEDominio_quandoEmailValido() {
        assertEquals("f***@email.com", LogUtils.maskEmail("fulano@email.com"));
    }
}
