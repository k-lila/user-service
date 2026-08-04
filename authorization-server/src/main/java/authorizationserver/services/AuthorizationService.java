package authorizationserver.services;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
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
    private final Duration emailVerificationGracePeriod;
    public AuthorizationService(
            IUserClient userClient,
            LoginAttemptService loginAttempts,
            @Value("${security.trusted-client-ip-header:CF-Connecting-IP}") String trustedClientIpHeader,
            @Value("${security.email-verification.grace-period:24h}") Duration emailVerificationGracePeriod) {
        this.userClient = userClient;
        this.loginAttempts = loginAttempts;
        this.trustedClientIpHeader = trustedClientIpHeader;
        this.emailVerificationGracePeriod = emailVerificationGracePeriod;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        LOGGER.info("| auth | carregando usuário | email: {}", LogUtils.maskEmail(email));
        AuthDTO user;
        try {
            user = userClient.getUserByEmail(email);
        } catch (AuthenticationException e) {
            // Propaga sem reembrulhar. AMBOS os casos chegam aqui pelo fallback do circuit breaker
            // (UserClientFallbackFactory), que é quem os distingue — o Feign entrega 404 de negócio
            // e indisponibilidade real pelo mesmo caminho:
            //
            //  - UsernameNotFoundException: o fallback a lança quando a causa é FeignException
            //    .NotFound, ou seja, o user-service devolveu 404 (titular inexistente OU inativo).
            //    O DaoAuthenticationProvider a converte em BadCredentialsException → evento
            //    publicado → CONTA no lockout. Correto: é indistinguível de tentativa de
            //    adivinhação, e manter o atrito contra enumeração de e-mails importa.
            //
            //  - UserServiceUnavailableException (ADR-021): indisponibilidade real (500, 503,
            //    timeout, conexão recusada, circuito aberto). É InternalAuthenticationService-
            //    Exception, repropagada intacta e sem evento publicado — NÃO conta no lockout.
            //    Um outage não pode bloquear a conta de um usuário legítimo por 15 min.
            //
            // O catch TEM de ser AuthenticationException (e não UsernameNotFoundException): a
            // segunda não é subtipo da primeira, cairia no catch (Exception) abaixo e seria
            // convertida de volta em UsernameNotFoundException — reintroduzindo o bug inteiro,
            // com todos os testes existentes passando. Guard: AuthorizationServiceTest
            // .deveNaoConverterEmUsernameNotFound_quandoUserServiceIndisponivel.
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
        // Defesa em profundidade: pelo caminho Feign este branch é inalcançável, porque o
        // user-service devolve 404 (não 200 com corpo nulo/inativo) para titular inexistente ou
        // inativo, e o 404 é resolvido no fallback acima. Mantido para o caso de o contrato do
        // canal interno mudar — sem isso, um corpo inesperado viraria NPE mais adiante.
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
        // tratado como verificado. Cadastros novos (ADR-015) nascem com emailVerified=false
        // e só viram true após a confirmação — mas ficam dentro de uma janela de carência
        // (grace period) desde o cadastro, para não tornar a conta permanentemente
        // inacessível caso o e-mail nunca chegue (SMTP fora do ar, outbox FAILED, etc.):
        // o reenvio manual é a única saída fora dessa janela.
        boolean withinGracePeriod = user.getRegistrationDate() != null
                && user.getRegistrationDate().plus(emailVerificationGracePeriod).isAfter(Instant.now());
        boolean emailVerified = !Boolean.FALSE.equals(user.getEmailVerified()) || withinGracePeriod;
        return new User(user.getEmail(), user.getPasswordHash(),
                emailVerified, true, true, accountNonLocked, authorities);
    }

}
