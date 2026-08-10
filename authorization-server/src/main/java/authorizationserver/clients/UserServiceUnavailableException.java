package authorizationserver.clients;

import org.springframework.security.authentication.InternalAuthenticationServiceException;

/**
 * Indisponibilidade do user-service (circuito aberto, timeout, erro de rede) no caminho de
 * autenticação.
 *
 * <p><b>Estende {@link InternalAuthenticationServiceException}, nunca
 * {@code UsernameNotFoundException}</b> (ADR-021). Esta é repropagada intacta pelo provider, e o
 * publisher resolve eventos por <b>nome exato de classe</b>: sem mapping, nenhum evento é publicado
 * e o {@code LoginAttemptListener} nunca dispara. O
 * {@code AbstractAuthenticationProcessingFilter} a captura, loga em ERROR e preserva o redirect
 * para {@code /login?error} — sem 5xx.
 *
 * <p>Com {@code UsernameNotFoundException} a cadeia seria: {@code DaoAuthenticationProvider} (com
 * {@code hideUserNotFoundExceptions=true}, o default) converte em {@code BadCredentialsException}
 * <b>sem encadear a causa</b> → evento publicado → contador de lockout incrementado, e cinco
 * tentativas durante um outage bloqueariam o par (conta, IP) por 15 min. Como a causa se perde na
 * conversão, distinguir os casos <i>dentro</i> do listener exigiria um ThreadLocal — daí o tipo
 * dedicado, e não uma flag.
 *
 * <p><b>SEM `cause` encadeada — deliberado.</b> O {@code SimpleUrlAuthenticationFailureHandler}
 * guarda a {@code AuthenticationException} na sessão (Spring Session + Redis, serialização JDK).
 * Anexar a causa real (cadeia Feign/Resilience4j) a torna não-serializável e faz o
 * {@code RedisSessionRepository.save} estourar {@code SerializationException} em <i>todo</i> login
 * falho durante um outage — pior que o bug do lockout. A causa não se perde: o
 * {@code UserClientFallbackFactory} a registra em {@code WARN | [CIRCUIT-BREAKER]} antes de lançar.
 */
public class UserServiceUnavailableException extends InternalAuthenticationServiceException {

    public UserServiceUnavailableException(String message) {
        super(message);
    }
}
