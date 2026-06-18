package authorizationserver.util;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolve o IP do request HTTP corrente. Fonte ÚNICA de IP para o lockout
 * (consultada tanto no {@code AuthorizationService} quanto no
 * {@code LoginAttemptListener}), garantindo a mesma chave Redis nos dois pontos.
 *
 * <p>Ordem de resolução: (1) o header de IP confiável injetado pela borda
 * ({@code trustedHeader}, default {@code CF-Connecting-IP}) — sob Cloudflare Tunnel
 * é o IP real do cliente, que a Cloudflare <b>sempre sobrescreve</b> (não-falsificável,
 * pois só o {@code cloudflared} alcança o serviço); (2) fallback em
 * {@code getRemoteAddr()}, que com {@code server.forward-headers-strategy=framework}
 * reflete o {@code X-Forwarded-For} sanitizado por uma borda confiável, e em dev (browser
 * direto) é o IP real. Fora de um request (ex.: teste unitário), devolve {@code "unknown"}.
 *
 * <p><b>Invariante de confiança:</b> o {@code trustedHeader} só é confiável porque nada
 * além da borda (cloudflared) alcança o auth-server na topologia base; se o serviço for
 * exposto direto, o header passa a ser falsificável.
 */
public final class ClientIpResolver {

    private static final String UNKNOWN = "unknown";

    private ClientIpResolver() {
    }

    public static String currentIp(String trustedHeader) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            if (trustedHeader != null && !trustedHeader.isBlank()) {
                String headerIp = request.getHeader(trustedHeader);
                if (headerIp != null && !headerIp.isBlank()) {
                    return headerIp.trim();
                }
            }
            String ip = request.getRemoteAddr();
            return ip != null ? ip : UNKNOWN;
        }
        return UNKNOWN;
    }
}
