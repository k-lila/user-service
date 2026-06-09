package authorizationserver.clients;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class UserClientFallbackFactoryTest {

    private final UserClientFallbackFactory factory = new UserClientFallbackFactory();

    @Test
    void deveLancarUsernameNotFoundException_quandoFallbackAcionado() {
        IUserClient fallback = factory.create(new RuntimeException("connection refused"));

        assertThrows(UsernameNotFoundException.class,
                () -> fallback.getUserByEmail("fulano@email.com"));
    }

    @Test
    void deveRetornarClienteNaoNulo_independenteDaCausa() {
        assertNotNull(factory.create(new RuntimeException("timeout")));
    }

    @Test
    void deveLancarUsernameNotFoundException_comEmailNulo() {
        IUserClient fallback = factory.create(new RuntimeException("timeout"));

        assertThrows(UsernameNotFoundException.class,
                () -> fallback.getUserByEmail(null));
    }

    @Test
    void deveLancarUsernameNotFoundException_comEmailSemArroba() {
        IUserClient fallback = factory.create(new RuntimeException("timeout"));

        assertThrows(UsernameNotFoundException.class,
                () -> fallback.getUserByEmail("naotemarroba"));
    }

    @Test
    void deveLancarUsernameNotFoundException_comEmailLocalCurto() {
        // email com parte local de 1 char — substring(0, min(2, at)) não estoura
        IUserClient fallback = factory.create(new RuntimeException("timeout"));

        assertThrows(UsernameNotFoundException.class,
                () -> fallback.getUserByEmail("a@dominio.com"));
    }

    @Test
    void deveLancarUsernameNotFoundException_quandoCausaSemMensagem() {
        // cause.getMessage() retorna null — SLF4J loga "null" sem estourar
        IUserClient fallback = factory.create(new RuntimeException());

        assertThrows(UsernameNotFoundException.class,
                () -> fallback.getUserByEmail("fulano@email.com"));
    }
}
