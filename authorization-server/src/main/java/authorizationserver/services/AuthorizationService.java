package authorizationserver.services;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import authorizationserver.clients.IUserClient;
import authorizationserver.dtos.AuthDTO;
import authorizationserver.util.ClientIpResolver;
import authorizationserver.util.LogUtils;

@Service
public class AuthorizationService implements UserDetailsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationService.class);
    private final IUserClient userClient;
    private final LoginAttemptService loginAttempts;
    private final String trustedClientIpHeader;
    public AuthorizationService(
            IUserClient userClient,
            LoginAttemptService loginAttempts,
            @Value("${security.trusted-client-ip-header:CF-Connecting-IP}") String trustedClientIpHeader) {
        this.userClient = userClient;
        this.loginAttempts = loginAttempts;
        this.trustedClientIpHeader = trustedClientIpHeader;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        LOGGER.info("| auth | carregando usuário | email: {}", LogUtils.maskEmail(email));
        AuthDTO user;
        try {
            user = userClient.getUserByEmail(email);
        } catch (UsernameNotFoundException e) {
            // Propaga sem reembrulhar: vem do fallback (user-service indisponível /
            // circuit breaker aberto) ou de "não encontrado". O DaoAuthenticationProvider
            // trata como credenciais inválidas (mensagem genérica) e devolve o usuário ao
            // login — em vez de escalar a 500 como o antigo catch (Exception) fazia.
            throw e;
        } catch (Exception e) {
            // Só o inesperado (ex.: erro de (de)serialização) cai aqui. Loga o detalhe
            // para diagnóstico, mas devolve UsernameNotFoundException (não RuntimeException)
            // para não escalar a 500 nem vazar a causa ao usuário.
            LOGGER.error(
                "| auth | falha inesperada ao carregar usuário | email: {}",
                LogUtils.maskEmail(email),
                e
            );
            throw new UsernameNotFoundException("Não foi possível autenticar o usuário");
        }
        if (user == null || !user.getActive()) {
            LOGGER.warn("| auth | inexistente ou inativo | email: {}", LogUtils.maskEmail(email));
            throw new UsernameNotFoundException("Usuário inexistente ou inativo: " + email);
        }
        // userId embutido como authority para que o jwtCustomizer o leia sem nova chamada Feign.
        // Filtrado em TokenCustomizerConfig: nunca entra no JWT como role ou permission.
        List<GrantedAuthority> authorities = new ArrayList<>();
        user.getRoles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        authorities.add(new SimpleGrantedAuthority("USER_ID:" + user.getId()));
        // Lockout anti-brute-force: se o par (conta, IP) atingiu o limite de falhas,
        // accountNonLocked=false faz o DaoAuthenticationProvider lançar LockedException
        // antes de checar a senha. Chaveado pelo email submetido (mesmo valor do listener).
        boolean accountNonLocked = !loginAttempts.isBlocked(email, ClientIpResolver.currentIp(trustedClientIpHeader));
        // emailVerified nulo (legado, anterior ao campo, ou falha de (de)serialização) é
        // tratado como verificado — sem fluxo de verificação implementado ainda, o gate
        // só passa a bloquear quando o campo for explicitamente setado como false.
        boolean emailVerified = !Boolean.FALSE.equals(user.getEmailVerified());
        return new User(user.getEmail(), user.getPasswordHash(),
                emailVerified, true, true, accountNonLocked, authorities);
    }

}
