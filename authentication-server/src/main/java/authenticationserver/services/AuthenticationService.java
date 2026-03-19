package authenticationserver.services;

import java.util.Date;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import authenticationserver.clients.IUserClient;
import authenticationserver.dtos.AuthDTO;
import authenticationserver.dtos.TokenDTO;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Serviço de autenticação", description = "Serviços de autenticação de usuários")
@Service
public class AuthenticationService {
    private final IUserClient userClient;
    private final PasswordEncoder passwordEncoder;
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationService.class);
    private static final String SECRET_HARENATOR_KEY = "chave-super-secreta-do-harenator";

    public AuthenticationService(IUserClient userClient, PasswordEncoder passwordEncoder) {
        this.userClient = userClient;
        this.passwordEncoder = passwordEncoder;
    }

    private String generateToken(AuthDTO user) {
        SecretKey secretKey = Keys.hmacShaKeyFor(SECRET_HARENATOR_KEY.getBytes());
        return Jwts.builder()
                .subject(user.getId())
                .issuer("authentication-server")
                .claim("email", user.getEmail())
                .claim("active", user.getActive())
                .claim("roles", user.getRoles())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(secretKey) 
                .compact();
    }

    public TokenDTO authenticate(String email, String password) {
        AuthDTO user;
        try {
            user = userClient.getUserByEmail(email);
        } catch (Exception e) {
            throw new BadCredentialsException("Usuário não encontrado");
        }
        Boolean isPasswordValid = passwordEncoder.matches(password, user.getPasswordHash());
        if (!isPasswordValid) {
            LOGGER.info(
                "| login negado | email: {}",
                email
            );
            throw new BadCredentialsException("Senha incorreta");
        }
        LOGGER.info(
            "| login realizado | email: {}",
            email
        );
        return new TokenDTO(generateToken(user));
    }

}
