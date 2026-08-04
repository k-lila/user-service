package authorizationserver.clients;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * ADR-021: o fallback lança {@link UserServiceUnavailableException}, NÃO
 * {@code UsernameNotFoundException}. Trocar de volta faz um outage do user-service alimentar o
 * contador de lockout e bloquear contas legítimas por 15 min.
 */
class UserClientFallbackFactoryTest {

    private final UserClientFallbackFactory factory = new UserClientFallbackFactory();

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
}
