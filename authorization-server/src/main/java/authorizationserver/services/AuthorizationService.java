package authorizationserver.services;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public AuthorizationService(IUserClient userClient, LoginAttemptService loginAttempts) {
        this.userClient = userClient;
        this.loginAttempts = loginAttempts;
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
        boolean accountNonLocked = !loginAttempts.isBlocked(email, ClientIpResolver.currentIp());
        return new User(user.getEmail(), user.getPasswordHash(),
                true, true, true, accountNonLocked, authorities);
    }

}
