package authorizationserver.session;

import java.time.Instant;

import jakarta.servlet.http.HttpSession;

/**
 * Instante em que a sessão do IdP foi <b>autenticada</b> (ADR-025) — carimbado por
 * {@code AuthenticationInstantListener} no login e lido pelo
 * {@code AuthorizationEndpointRevalidationFilter}.
 *
 * <p><b>Nunca {@code getCreationTime()}.</b> No BFF a sessão nasce <i>antes</i> do login, para
 * carregar o token CSRF e o saved request do {@code /oauth2/authorize} — caminho normal, não
 * exceção. Ancorar o teto de vida na criação puniria o tempo de sessão <b>anônima</b>: uma aba
 * aberta há 8h faria o login legítimo nascer vencido. O teto mede "há quanto tempo esta credencial
 * foi provada".
 *
 * <p><b>O tipo é requisito, não detalhe:</b> {@link Long} de epoch millis, nunca {@link Instant}
 * nem tipo próprio. A sessão é Spring Session sobre Redis com serialização JDK e este atributo é
 * escrito em <b>todo</b> login bem-sucedido — um valor não-serializável quebraria o login inteiro,
 * não um caminho de borda (precedente no ADR-021). O nome do atributo é constante única, lida pelo
 * listener e pelo filtro; nunca duplique a string literal.
 */
public final class AuthenticationInstantAttribute {

    /** Nome do atributo na {@link HttpSession}. Fonte única — listener e filtro leem daqui. */
    public static final String NAME = "AUTH_INSTANT";

    private AuthenticationInstantAttribute() {
    }

    /** Grava o carimbo como {@link Long} de epoch millis (ver o javadoc da classe). */
    public static void write(HttpSession session, Instant instant) {
        session.setAttribute(NAME, Long.valueOf(instant.toEpochMilli()));
    }

    /**
     * Carimbo da sessão, com {@code getCreationTime()} como <b>fallback</b> quando o atributo não
     * existe — sessões criadas antes do deploy do ADR-025. Não lança e não exige migração: para uma
     * sessão anterior, a criação é a melhor aproximação disponível do instante de autenticação.
     */
    public static Instant read(HttpSession session) {
        Object value = session.getAttribute(NAME);
        if (value instanceof Long millis) {
            return Instant.ofEpochMilli(millis);
        }
        return Instant.ofEpochMilli(session.getCreationTime());
    }

    /** {@code true} se o carimbo existe de fato (i.e. {@link #read} não caiu no fallback). */
    public static boolean isPresent(HttpSession session) {
        return session.getAttribute(NAME) instanceof Long;
    }
}
