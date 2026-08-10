package authorizationserver.filter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import authorizationserver.services.RevocationRefreshGuard;
import authorizationserver.session.AuthenticationInstantAttribute;
import authorizationserver.util.LogUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Re-derivação do estado do titular <b>na emissão</b> do authorization code (ADR-025).
 * <b>Leia o ADR-025 antes de alterar este filtro.</b>
 *
 * <p><b>O defeito.</b> Com {@code requireAuthorizationConsent(false)} e sessão do IdP viva, o SAS
 * emite o código <b>sem</b> chamar {@code loadUserByUsername}, e o {@code TokenCustomizerConfig}
 * cunha os claims a partir das authorities <b>congeladas no login</b>. As três checagens da ADR-017
 * comparam {@code iat < epoch} e o token reemitido nasce com {@code iat = agora}: todas aprovam
 * <b>por construção</b> — a revogação não falha, é <i>contornada</i>.
 *
 * <p><b>Cinco coisas a NÃO regredir:</b>
 *
 * <p>(1) <b>Re-derivar pelo mesmo {@link UserDetailsService} do form login</b> (AC-08), nunca pelo
 * Feign direto. O reuso herda o gate de {@code active}, o de e-mail com carência (ADR-015) e a
 * distinção 404-de-negócio × indisponibilidade (ADR-021), e elimina o laço {@code /login} ↔
 * {@code /oauth2/authorize}: com {@code enabled=false} o form login aplica o mesmo gate e a
 * {@code DisabledException} aparece em {@code /login?error} antes de o filtro poder ciclar.
 *
 * <p>(2) <b>Nunca via {@code AuthenticationManager}/{@code ProviderManager}</b>: publicaria
 * {@code AuthenticationSuccessEvent}, e o {@code LoginAttemptListener} <b>zeraria</b> o contador de
 * lockout do ADR-010 a cada authorize — bypass de controle sem prova de senha. Pelo mesmo evento o
 * carimbo de instante de autenticação seria re-emitido, esvaziando o teto de vida e a degradação
 * por epoch.
 *
 * <p>(3) <b>Divergência invalida a sessão; não atualiza em cima</b> (AC-06) — o re-login re-deriva
 * o estado por definição, que é o caminho já testado.
 *
 * <p>(4) <b>{@code accountNonLocked} fica deliberadamente fora</b> da comparação: se conta
 * bloqueada invalidasse sessão viva, qualquer um derrubaria a sessão de outro errando cinco senhas
 * — o lockout do ADR-010 viraria DoS.
 *
 * <p>(5) <b>A comparação inclui a authority {@code USER_ID:}</b>, não só as {@code ROLE_*}: é dela
 * que sai o claim {@code userID}, e comparar só roles deixaria um delete + re-registro com o mesmo
 * e-mail cunhar token cujo {@code userID} aponta para documento inexistente.
 *
 * <p><b>Sessão inválida vira 302 {@code /login} sem redirect à mão:</b> o filtro invalida, limpa o
 * {@code SecurityContext} e segue a chain; sem autenticação o {@code AuthorizationFilter} nega, o
 * {@code ExceptionTranslationFilter} salva o request e comissiona o
 * {@code LoginUrlAuthenticationEntryPoint}, e o {@code SavedRequestAwareAuthenticationSuccessHandler}
 * retoma o fluxo pedido após o re-login — sem laço nem tela morta.
 */
public class AuthorizationEndpointRevalidationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuthorizationEndpointRevalidationFilter.class);

    private static final String USER_ID_PREFIX = "USER_ID:";
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * Motivo da invalidação, discriminado no log (AC-37) — sem ele, verificar a correção em
     * produção exige perícia manual no Postgres e no Redis.
     */
    public enum Reason {
        /**
         * Titular inexistente ou inativo na fonte de verdade. <b>Sinal ambíguo por desenho:</b> tem
         * duas causas legítimas — eliminação (hard/soft delete) <i>e</i> titular que trocou o
         * próprio e-mail, caso em que o {@code principal_name} da sessão é o antigo e a
         * re-derivação procura por ele (ADR-025, P-01).
         */
        NOT_FOUND,
        /** {@code enabled=false}: e-mail não verificado fora da carência de 24h (ADR-015). */
        DISABLED,
        /** Authorities da sessão divergem das re-derivadas — inclui divergência de {@code USER_ID:}. */
        AUTHORITIES_DIVERGED,
        /** Instante de autenticação mais antigo que {@code security.session.max-lifetime}. */
        MAX_LIFETIME,
        /** Caminho degradado (fonte de verdade fora): epoch de revogação posterior à autenticação. */
        REVOKED_EPOCH
    }

    private final RequestMatcher matcher;
    private final UserDetailsService userDetailsService;
    private final RevocationRefreshGuard revocationGuard;
    private final Duration maxLifetime;
    private final boolean enabled;

    public AuthorizationEndpointRevalidationFilter(
            String authorizationEndpoint,
            UserDetailsService userDetailsService,
            RevocationRefreshGuard revocationGuard,
            Duration maxLifetime,
            boolean enabled) {
        // MATCHER POSITIVO E EXATO, derivado de AuthorizationServerSettings.getAuthorizationEndpoint()
        // — nunca do literal "/oauth2/authorize" (AC-33). Duas regressões silenciosas evitadas:
        //  (1) A chain @Order(1) casa TODO o endpointsMatcher do SAS (/oauth2/token, /connect/logout
        //      via oidc(), /oauth2/jwks...); sem este recorte o filtro atuaria no back-channel e no
        //      logout. Enumerar EXCLUSÕES é o padrão que produziu o G1 — matcher positivo transforma
        //      "esqueci de excluir X" em "X nunca esteve incluído".
        //  (2) Um `.authorizationEndpoint(...)` customizado faria o literal parar de casar EM
        //      SILÊNCIO: fix inerte, build verde, defeito reaberto.
        this.matcher = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, authorizationEndpoint),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, authorizationEndpoint));
        this.userDetailsService = userDetailsService;
        this.revocationGuard = revocationGuard;
        this.maxLifetime = maxLifetime;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // O filtro permanece NA CHAIN mesmo desabilitado (a guarda estrutural do AC-34 não depende de
        // propriedade); o toggle é reversão operacional e apenas o torna no-op.
        return !this.enabled || !this.matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (!isAuthenticatedTitular(authentication) || session == null) {
            // Authorize anônimo é o caminho normal do primeiro acesso: nada a re-derivar, o
            // ExceptionTranslationFilter cuida do redirect ao formulário.
            chain.doFilter(request, response);
            return;
        }

        Reason reason = evaluate(authentication, session);
        if (reason != null) {
            invalidate(session, authentication, reason);
        }
        chain.doFilter(request, response);
    }

    /** {@code null} = sessão íntegra, emissão segue normalmente. */
    private Reason evaluate(Authentication authentication, HttpSession session) {
        String username = authentication.getName();
        String userId = userIdFrom(authentication);
        Instant authenticatedAt = AuthenticationInstantAttribute.read(session);

        // Teto de vida ANTES da chamada remota: sessão vencida não precisa de I/O para morrer. Os
        // 30 min do Spring Session são de INATIVIDADE e cada autorização os renova — sem teto
        // ancorado no instante de autenticação, sessão em uso contínuo nunca expira.
        if (isPastMaxLifetime(authenticatedAt)) {
            return Reason.MAX_LIFETIME;
        }

        UserDetails fresh;
        try {
            fresh = this.userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            // 404 de negócio (inexistente OU inativo), resolvido no UserClientFallbackFactory.
            // Fail-CLOSED aqui é o ponto inteiro da correção. Ressalva aceita na ADR-025: o
            // catch (Exception) do AuthorizationService converte falha inesperada (ex.:
            // (de)serialização) nesta mesma exceção, então um erro raro desloga em vez de degradar
            // — preferível a um terceiro ramo que adivinharia a causa.
            return Reason.NOT_FOUND;
        } catch (AuthenticationException e) {
            // UserServiceUnavailableException (ADR-021) e afins: indisponibilidade real (500, 503,
            // timeout, conexão recusada, circuito aberto). NÃO propaga e NÃO invalida por si só —
            // outage do user-service não pode derrubar o login de todos. Degrada para a checagem
            // que só precisa do Redis.
            LOGGER.warn(
                    "| revalidação | fonte de verdade indisponível | degradando para o epoch (fail-open) | email: {} | causa: {}",
                    LogUtils.maskEmail(username), e.toString());
            // isRevoked é fail-open por dentro (ADR-017): Redis fora → false → a emissão segue,
            // simétrico ao resto do sistema.
            return this.revocationGuard.isRevoked(userId, authenticatedAt) ? Reason.REVOKED_EPOCH : null;
        }

        if (!fresh.isEnabled()) {
            return Reason.DISABLED;
        }
        if (!authoritiesMatch(authentication, fresh)) {
            return Reason.AUTHORITIES_DIVERGED;
        }
        return null;
    }

    private boolean isPastMaxLifetime(Instant authenticatedAt) {
        if (this.maxLifetime == null || this.maxLifetime.isZero() || this.maxLifetime.isNegative()) {
            return false;
        }
        return authenticatedAt.plus(this.maxLifetime).isBefore(Instant.now());
    }

    /**
     * Compara as authorities <b>de domínio</b> — {@code ROLE_*} e {@code USER_ID:} — entre a sessão
     * e a fonte de verdade. {@code accountNonLocked} não entra (ver o javadoc da classe).
     *
     * <p><b>O recorte por prefixo é obrigatório; conjunto cru não serve.</b> O
     * {@code DaoAuthenticationProvider} do Spring Security 7 acrescenta ao token do login
     * authorities de <b>fator de autenticação</b> ({@code FACTOR_PASSWORD}) que o
     * {@link UserDetails} não tem — do framework, não do domínio. Comparar os conjuntos crus faria
     * TODA sessão divergir de si mesma e invalidaria todo login: a pior regressão desta correção, e
     * a que o smoke-test do ADR-023 existe para distinguir do fix correto. O recorte compara
     * exatamente o que o {@code AuthorizationService} deriva e o que o
     * {@code TokenCustomizerConfig} consome.
     */
    private boolean authoritiesMatch(Authentication authentication, UserDetails fresh) {
        return domainAuthorities(authentication.getAuthorities())
                .equals(domainAuthorities(fresh.getAuthorities()));
    }

    private Set<String> domainAuthorities(java.util.Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(ROLE_PREFIX) || a.startsWith(USER_ID_PREFIX))
                .collect(Collectors.toSet());
    }

    private boolean isAuthenticatedTitular(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private String userIdFrom(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(USER_ID_PREFIX))
                .findFirst()
                .map(a -> a.substring(USER_ID_PREFIX.length()))
                .orElse(null);
    }

    private void invalidate(HttpSession session, Authentication authentication, Reason reason) {
        String userId = userIdFrom(authentication);
        LOGGER.info(
                "| revalidação | sessão do IdP invalidada na emissão | motivo: {} | email: {} | ID: {}",
                reason, LogUtils.maskEmail(authentication.getName()), userId != null ? userId : "-");
        try {
            session.invalidate();
        } catch (IllegalStateException e) {
            // Já invalidada por outro caminho no mesmo request: o efeito desejado já é o que se
            // tem. Não é condição de erro.
            LOGGER.debug("| revalidação | sessão já invalidada | motivo: {}", reason);
        }
        SecurityContextHolder.clearContext();
    }
}
