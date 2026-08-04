package authorizationserver.clients;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;

/**
 * ADR-021: o fallback separa DOIS desfechos que o Feign entrega pelo mesmo caminho.
 *
 * <ul>
 *   <li><b>404</b> → {@code UsernameNotFoundException}: titular inexistente/inativo é resultado de
 *       negócio e <b>conta no lockout</b> (atrito contra enumeração de e-mails).</li>
 *   <li><b>Qualquer outra causa</b> → {@link UserServiceUnavailableException}: indisponibilidade
 *       real, <b>não</b> conta no lockout — um outage não pode bloquear conta legítima por 15 min.</li>
 * </ul>
 *
 * <p>Inverter qualquer um dos dois ramos reintroduz um bug já corrigido, por isso ambos têm caso
 * dedicado — incluindo o de 500, que garante que o ramo do 404 não foi escrito com um
 * {@code instanceof FeignException} genérico (500/503/connection-refused também são
 * {@code FeignException}).
 */
class UserClientFallbackFactoryTest {

    private final UserClientFallbackFactory factory = new UserClientFallbackFactory();

    /** Reproduz o que o ErrorDecoder default do Feign produz para um dado status HTTP. */
    private static FeignException feignComStatus(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "/internal/users/email/x",
                Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus(
                "IUserClient#getUserByEmail(String)",
                feign.Response.builder()
                        .status(status)
                        .reason("erro")
                        .request(request)
                        .headers(Map.of())
                        .build());
    }

    @Test
    void deveLancarUserServiceUnavailable_quandoFallbackAcionado() {
        IUserClient fallback = factory.create(new RuntimeException("connection refused"));

        assertThrows(UserServiceUnavailableException.class,
                () -> fallback.getUserByEmail("fulano@email.com"));
    }

    @Test
    void deveRetornarClienteNaoNulo_independenteDaCausa() {
        assertNotNull(factory.create(new RuntimeException("timeout")));
    }

    @Test
    void deveLancarUserServiceUnavailable_comEmailNulo() {
        IUserClient fallback = factory.create(new RuntimeException("timeout"));

        assertThrows(UserServiceUnavailableException.class,
                () -> fallback.getUserByEmail(null));
    }

    @Test
    void deveLancarUserServiceUnavailable_comEmailSemArroba() {
        IUserClient fallback = factory.create(new RuntimeException("timeout"));

        assertThrows(UserServiceUnavailableException.class,
                () -> fallback.getUserByEmail("naotemarroba"));
    }

    @Test
    void deveLancarUserServiceUnavailable_comEmailLocalCurto() {
        // email com parte local de 1 char — substring(0, min(2, at)) não estoura
        IUserClient fallback = factory.create(new RuntimeException("timeout"));

        assertThrows(UserServiceUnavailableException.class,
                () -> fallback.getUserByEmail("a@dominio.com"));
    }

    @Test
    void deveLancarUserServiceUnavailable_quandoCausaSemMensagem() {
        // cause.getMessage() retorna null — SLF4J loga "null" sem estourar
        IUserClient fallback = factory.create(new RuntimeException());

        assertThrows(UserServiceUnavailableException.class,
                () -> fallback.getUserByEmail("fulano@email.com"));
    }

    // ── Ramo do 404: resultado de negócio, CONTA no lockout ──────────────────────────────────

    @Test
    void deveLancarUsernameNotFound_quandoCausaEh404() {
        IUserClient fallback = factory.create(feignComStatus(404));

        // UsernameNotFoundException → o provider converte em BadCredentials → evento publicado →
        // LoginAttemptListener incrementa. É o que mantém o atrito contra enumeração de e-mails.
        assertThrows(UsernameNotFoundException.class,
                () -> fallback.getUserByEmail("inexistente@email.com"));
    }

    // Não há teste de "404 não lança UserServiceUnavailableException": as duas hierarquias são
    // disjuntas e o compilador rejeita o instanceof como tipo impossível — garantia mais forte que
    // uma asserção em runtime. O assertThrows acima já quebra se alguém religar a conversão.

    // GUARD do `instanceof`: 500 também é FeignException. Se o ramo do 404 tivesse sido escrito
    // como `instanceof FeignException` genérico, este teste pegaria — um outage voltaria a
    // alimentar o lockout, que é exatamente o bug que o ADR-021 fechou.
    @Test
    void deveLancarUserServiceUnavailable_quandoCausaEh500() {
        IUserClient fallback = factory.create(feignComStatus(500));

        assertThrows(UserServiceUnavailableException.class,
                () -> fallback.getUserByEmail("fulano@email.com"));
    }

    // 503 é o que o FeignBlockingLoadBalancerClient devolve quando não há instância no Eureka —
    // indisponibilidade real, mesmo ramo do 500.
    @Test
    void deveLancarUserServiceUnavailable_quandoCausaEh503() {
        IUserClient fallback = factory.create(feignComStatus(503));

        assertThrows(UserServiceUnavailableException.class,
                () -> fallback.getUserByEmail("fulano@email.com"));
    }
}
