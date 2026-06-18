package com.users.gateway.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

/**
 * Unitários do {@link ClientIpResolver} (edge reativo): precedência do header confiável
 * (CF-Connecting-IP), fallback no remoteAddress e que o X-Forwarded-For bruto é ignorado.
 */
class ClientIpResolverTest {

    @Test
    void devePreferirHeaderConfiavel_quandoPresente() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .header("CF-Connecting-IP", "203.0.113.5")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1234))
                .build();

        assertThat(ClientIpResolver.resolve(request, "CF-Connecting-IP")).isEqualTo("203.0.113.5");
    }

    @Test
    void deveIgnorarXForwardedFor_eCairNoRemoteAddress() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1")
                .remoteAddress(new InetSocketAddress("198.51.100.7", 1234))
                .build();

        assertThat(ClientIpResolver.resolve(request, "CF-Connecting-IP")).isEqualTo("198.51.100.7");
    }

    @Test
    void deveCairNoRemoteAddress_quandoHeaderConfiavelAusente() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("198.51.100.7", 1234))
                .build();

        assertThat(ClientIpResolver.resolve(request, "CF-Connecting-IP")).isEqualTo("198.51.100.7");
    }

    @Test
    void deveRetornarUnknown_quandoSemHeaderESemRemoteAddress() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/").build();

        assertThat(ClientIpResolver.resolve(request, "CF-Connecting-IP")).isEqualTo("unknown");
    }

    @Test
    void deveIgnorarHeaderConfiavel_quandoNomeVazio() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .header("CF-Connecting-IP", "203.0.113.5")
                .remoteAddress(new InetSocketAddress("198.51.100.7", 1234))
                .build();

        assertThat(ClientIpResolver.resolve(request, "")).isEqualTo("198.51.100.7");
    }
}
