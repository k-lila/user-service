package authorizationserver.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import authorizationserver.clients.IUserClient;
import authorizationserver.dtos.AuthDTO;


@Configuration
public class TokenCustomizerConfig {

    private final IUserClient userClient;

    public TokenCustomizerConfig(IUserClient userClient) {
        this.userClient = userClient;
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            var authentication = context.getPrincipal();
            AuthDTO user = userClient.getUserByEmail(authentication.getName());
            context.getClaims().claims(claims -> {
                claims.put("userID", user.getId());
                claims.put("roles", user.getRoles());
                claims.put("permissions", List.of(
                        "users.read",
                        "users.write"
                ));
                claims.put("scope", context.getAuthorizedScopes());

            });
        };
    }
}