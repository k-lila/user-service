package authorizationserver.listeners;

import java.time.Instant;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import authorizationserver.session.AuthenticationInstantAttribute;

import jakarta.servlet.http.HttpSession;

/**
 * Carimba na sessão do IdP o instante em que ela foi <b>autenticada</b> (ADR-025), base do teto de
 * vida e da degradação por epoch do {@code AuthorizationEndpointRevalidationFilter}.
 *
 * <p>Mesmo padrão e pacote do {@link LoginAttemptListener}, inclusive o guard
 * {@code instanceof UsernamePasswordAuthenticationToken}, que restringe o carimbo ao <b>form login</b>
 * — a autenticação de client OAuth2 no token endpoint não é login de titular e não tem sessão a
 * carimbar.
 *
 * <p><b>A re-derivação do ADR-025 não passa por aqui, e isso é requisito (AC-38).</b> O filtro chama
 * {@code UserDetailsService.loadUserByUsername} <b>direto</b>, fora do
 * {@code AuthenticationManager}/{@code ProviderManager}, justamente para não publicar
 * {@link AuthenticationSuccessEvent}. Se publicasse, um único defeito quebraria dois controles de
 * uma vez: aqui, o carimbo avançaria a cada autorização e esvaziaria o teto de vida <i>pelo mesmo
 * mecanismo de auto-renovação que o incidente descreveu</i> — e também derrotaria a degradação por
 * epoch, porque {@code RevocationRefreshGuard.isRevoked} testa {@code epoch > instante} e um instante
 * sempre fresco devolveria {@code false} para sempre; e no {@link LoginAttemptListener}, o
 * {@code loginSucceeded} <b>zeraria</b> o lockout do ADR-010 sem prova de senha.
 *
 * <p>O carimbo sobrevive ao {@code changeSessionId} do login (a proteção contra session fixation
 * preserva os atributos). Sem sessão no request, não grava — o filtro cai no
 * {@code getCreationTime()} (ADR-025, fallback de sessão anterior ao deploy).
 */
@Component
public class AuthenticationInstantListener {

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (!(event.getAuthentication() instanceof UsernamePasswordAuthenticationToken)) {
            return;
        }
        HttpSession session = currentSession();
        if (session == null) {
            return;
        }
        AuthenticationInstantAttribute.write(session, Instant.now());
    }

    private HttpSession currentSession() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            // getSession(true): no BFF a sessão já existe (CSRF + saved request), mas o login por
            // outro caminho pode chegar sem ela — criar aqui é inócuo (o login criaria adiante) e
            // evita que o carimbo se perca justo no caminho que ele existe para medir.
            return attributes.getRequest().getSession(true);
        }
        return null;
    }
}
