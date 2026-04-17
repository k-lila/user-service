package authorizationserver.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;


@Configuration
public class TokenCustomizerConfig {
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            var authentication = context.getPrincipal();
            String username = authentication.getName();
            var roles = authentication.getAuthorities()
                    .stream()
                    .map(a -> a.getAuthority().replace("ROLE_", ""))
                    .toList();
            context.getClaims().claims(claims -> {
                claims.put("email", username);
                claims.put("roles", roles);
                claims.put("permissions", List.of(
                        "users.read",
                        "users.write"
                ));
                claims.put("scope", context.getAuthorizedScopes());

            });
        };
    }
}