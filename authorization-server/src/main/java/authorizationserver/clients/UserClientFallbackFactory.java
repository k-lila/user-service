package authorizationserver.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import feign.FeignException;

/**
 * Fallback do circuit breaker na chamada ao user-service.
 *
 * <p><b>Distingue dois desfechos que o Feign entrega pelo mesmo caminho</b> (ADR-021): o
 * {@code getAndApplyFallback} do Spring Cloud captura {@code Throwable} <b>sem filtro algum</b> —
 * nem por tipo, nem por {@code ignoreExceptions}, nem por estado do circuito —, então 404 de
 * negócio e indisponibilidade real chegam aqui juntos e precisam ser separados no código. Não há
 * atalho por configuração: {@code ignoreExceptions} impede o circuito de <i>abrir</i>, mas não
 * impede o fallback de ser <i>invocado</i>.
 *
 * <ul>
 *   <li><b>404</b> ({@code FeignException.NotFound}) — o titular não existe ou está inativo
 *       ({@code AuthenticationService} do user-service lança {@code DomainEntityNotFound} nos dois
 *       casos). É <b>resultado de negócio</b>, não falha de infraestrutura: vira
 *       {@code UsernameNotFoundException}, que o {@code DaoAuthenticationProvider} converte em
 *       {@code BadCredentialsException} → evento publicado → <b>conta no lockout</b>, mantendo o
 *       atrito contra enumeração de e-mails.</li>
 *   <li><b>Qualquer outra causa</b> — 500, 503 (LoadBalancer sem instância),
 *       {@code RetryableException} (conexão recusada), {@code TimeoutException} (TimeLimiter),
 *       {@code CallNotPermittedException} (circuito aberto) — é indisponibilidade real:
 *       {@link UserServiceUnavailableException}, que <b>não</b> gera evento e portanto
 *       <b>não conta no lockout</b>. Sem isso, um outage de cinco tentativas bloquearia a conta de
 *       um usuário legítimo por 15 minutos.</li>
 * </ul>
 *
 * <p><b>Não use {@code instanceof FeignException} genérico</b> para detectar o caso de negócio:
 * 500, 503 e connection-refused também são {@code FeignException}, e cairiam no ramo errado —
 * devolvendo ao lockout exatamente o que o ADR-021 tirou dele.
 */
@Component
public class UserClientFallbackFactory implements FallbackFactory<IUserClient> {

    private static final Logger log = LoggerFactory.getLogger(UserClientFallbackFactory.class);

    @Override
    public IUserClient create(Throwable cause) {
        return email -> {
            if (cause instanceof FeignException.NotFound) {
                // Rotina: e-mail digitado errado ou conta inativa. DEBUG, não WARN — em WARN, todo
                // login com e-mail inexistente poluiria o log operacional.
                log.debug("| auth | titular inexistente ou inativo | email: {}", maskEmail(email));
                throw new UsernameNotFoundException("user not found");
            }
            log.warn("| [CIRCUIT-BREAKER] | user-service indisponível | email: {} | cause: {}",
                    maskEmail(email), cause.getMessage());
            // A `cause` NÃO é encadeada em nenhum dos dois ramos (ver javadoc de
            // UserServiceUnavailableException): o failure handler guarda a AuthenticationException
            // na sessão Redis, e a cadeia Feign/Resilience4j não é serializável. Fica só no log.
            throw new UserServiceUnavailableException("user-service unavailable");
        };
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }
}
