package com.users.gateway.util;

import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Resolve o IP do cliente no edge reativo (WebFlux). Fonte ÚNICA de IP do gateway,
 * compartilhada entre o particionamento do rate limit ({@code RateLimiterConfig.ipKeyResolver})
 * e o log de rejeição ({@code RateLimitLogFilter}), garantindo a mesma chave nos dois pontos.
 *
 * <p>Ordem de resolução: (1) o header de IP confiável injetado pela borda
 * ({@code trustedHeader}, default {@code CF-Connecting-IP}) — sob Cloudflare Tunnel é o IP
 * real do cliente, que a Cloudflare <b>sempre sobrescreve</b> (não-falsificável, pois só o
 * {@code cloudflared} alcança o gateway); (2) fallback em
 * {@code getRemoteAddress().getHostString()}, que com {@code server.forward-headers-strategy=framework}
 * reflete o {@code X-Forwarded-For} sanitizado por uma borda confiável.
 *
 * <p>O {@code X-Forwarded-For} bruto <b>não</b> é mais lido diretamente: sob um proxy que faz
 * <i>append</i> (ex.: cloudflared), o primeiro elemento da lista é controlado pelo cliente —
 * era o vetor de spoofing do particionamento por IP.
 */
public final class ClientIpResolver {

    private static final String UNKNOWN = "unknown";

    private ClientIpResolver() {
    }

    public static String resolve(ServerHttpRequest request, String trustedHeader) {
        if (trustedHeader != null && !trustedHeader.isBlank()) {
            String headerIp = request.getHeaders().getFirst(trustedHeader);
            if (headerIp != null && !headerIp.isBlank()) {
                return headerIp.trim();
            }
        }
        var address = request.getRemoteAddress();
        if (address == null) {
            return UNKNOWN;
        }
        return address.getHostString();
    }
}
