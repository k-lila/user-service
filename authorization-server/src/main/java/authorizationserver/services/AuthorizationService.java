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

@Service
public class AuthorizationService implements UserDetailsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationService.class);
    private final IUserClient userClient;
    public AuthorizationService(IUserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        LOGGER.info("auth acionado | email: {}", email);
        AuthDTO user;
        try {
            user = userClient.getUserByEmail(email);
            if (!user.getActive()) {
                throw new UsernameNotFoundException("Usuário inativo: " + email);
            }
        } catch (Exception e) {
            throw new RuntimeException("Erro de comunicação interna entre serviços: " + e);
        }
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return new User(user.getEmail(), user.getPasswordHash(), authorities);
    }

}
