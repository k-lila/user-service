package authorizationserver.util;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolve o IP do request HTTP corrente. Fonte ÚNICA de IP para o lockout
 * (consultada tanto no {@code AuthorizationService} quanto no
 * {@code LoginAttemptListener}), garantindo a mesma chave Redis nos dois pontos.
 *
 * <p>Usa {@code getRemoteAddr()}: em desenvolvimento o browser navega direto
 * para o auth-server, então é o IP real. Em produção, atrás de um proxy, exige
 * {@code server.forward-headers-strategy} para refletir o {@code X-Forwarded-For}
 * (e o proxy deve sobrescrever esse header, sob pena de spoofing). Fora de um
 * request (ex.: teste unitário), devolve {@code "unknown"}.
 */
public final class ClientIpResolver {

    private static final String UNKNOWN = "unknown";

    private ClientIpResolver() {
    }

    public static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String ip = attrs.getRequest().getRemoteAddr();
            return ip != null ? ip : UNKNOWN;
        }
        return UNKNOWN;
    }
}
