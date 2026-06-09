package authorizationserver.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
            // Permissions derivadas das roles reais do usuário (em vez de fixas para todos):
            // ADMIN ganha users.delete. LinkedHashSet deduplica (USER+ADMIN não repete
            // read/write) e mantém ordem estável.
            Set<String> permissions = new LinkedHashSet<>();
            for (String role : user.getRoles()) {
                switch (role) {
                    case "USER"  -> permissions.addAll(List.of("users.read", "users.write"));
                    case "ADMIN" -> permissions.addAll(List.of("users.read", "users.write", "users.delete"));
                    default      -> { /* role desconhecida: sem permissions */ }
                }
            }
            context.getClaims().claims(claims -> {
                claims.put("userID", user.getId());
                claims.put("roles", user.getRoles());
                // ArrayList (não List.of / não o Set direto): o JdbcOAuth2AuthorizationService
                // serializa os claims no Postgres com type-id, e o PolymorphicTypeValidator do SAS
                // rejeita java.util.ImmutableCollections$* / Set$* na releitura (ex.: /userinfo).
                claims.put("permissions", new ArrayList<>(permissions));
                claims.put("scope", context.getAuthorizedScopes());

            });
        };
    }
}