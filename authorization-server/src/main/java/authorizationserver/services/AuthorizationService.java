package authorizationserver.services;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import authorizationserver.clients.IUserClient;
import authorizationserver.dtos.AuthDTO;
import authorizationserver.util.LogUtils;

@Service
public class AuthorizationService implements UserDetailsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationService.class);
    private final IUserClient userClient;
    public AuthorizationService(IUserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        LOGGER.info("| auth | carregando usuário | email: {}", LogUtils.maskEmail(email));
        AuthDTO user;
        try {
            user = userClient.getUserByEmail(email);
        } catch (Exception e) {
            LOGGER.error(
                "| auth | falha Feign user-service | email: {}",
                LogUtils.maskEmail(email),
                e
            );
            throw new RuntimeException("Erro de comunicação interna entre serviços");
        }
        if (!user.getActive()) {
            LOGGER.warn("| auth | inexistente ou inativo | email: {}", LogUtils.maskEmail(email));
            throw new UsernameNotFoundException("Usuário inativo: " + email);
        }
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return new User(user.getEmail(), user.getPasswordHash(), authorities);
    }

}
