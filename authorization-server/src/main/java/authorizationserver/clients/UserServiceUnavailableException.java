package authorizationserver.clients;

import org.springframework.security.authentication.InternalAuthenticationServiceException;

/**
 * Indisponibilidade do user-service (circuito aberto, timeout, erro de rede) no caminho de
 * autenticação.
 *
 * <p>Estende {@link InternalAuthenticationServiceException} — e <b>não</b>
 * {@code UsernameNotFoundException} — de propósito (ADR-021). Com {@code UsernameNotFoundException}
 * a cadeia era: {@code DaoAuthenticationProvider} (com {@code hideUserNotFoundExceptions=true}, o
 * default) converte em {@code BadCredentialsException} <b>sem encadear a causa</b> → o
 * {@code DefaultAuthenticationEventPublisher} emite {@code AuthenticationFailureBadCredentialsEvent}
 * → o {@code LoginAttemptListener} incrementa o contador de lockout. Resultado: cinco tentativas
 * durante um outage bloqueavam o par (conta, IP) por 15 minutos — uma indisponibilidade virava
 * negação de serviço para o usuário legítimo.
 *
 * <p>{@code InternalAuthenticationServiceException} é repropagada intacta pelo provider, e o
 * publisher resolve eventos por <b>nome exato de classe</b>: sem mapping para esta, nenhum evento
 * é publicado e o listener nunca dispara. O {@code AbstractAuthenticationProcessingFilter} a
 * captura, loga em ERROR e preserva o redirect para {@code /login?error} — sem 5xx.
 *
 * <p>Como a causa não é encadeada na conversão, distinguir os dois casos <i>dentro</i> do listener
 * seria impossível sem um ThreadLocal — daí o tipo dedicado ser a solução, e não uma flag.
 *
 * <p><b>SEM `cause` encadeada — deliberado.</b> O {@code SimpleUrlAuthenticationFailureHandler}
 * guarda a {@code AuthenticationException} na sessão HTTP sob
 * {@code WebAttributes.AUTHENTICATION_EXCEPTION}, e a sessão é Spring Session + Redis com
 * serialização JDK. Anexar a causa real (a cadeia Feign/Resilience4j) torna a exceção
 * não-serializável e faz o {@code RedisSessionRepository.save} estourar
 * {@code SerializationException} em <i>todo</i> login falho durante um outage — trocando o bug do
 * lockout por um pior. A causa não se perde: o {@code UserClientFallbackFactory} já a registra em
 * {@code WARN | [CIRCUIT-BREAKER]} antes de lançar.
 */
public class UserServiceUnavailableException extends InternalAuthenticationServiceException {

    public UserServiceUnavailableException(String message) {
        super(message);
    }
}
