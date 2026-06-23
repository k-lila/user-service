package authorizationserver.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import authorizationserver.services.RevocationRefreshGuard;

@Configuration
public class TokenCustomizerConfig {

    private static final String USER_ID_PREFIX = "USER_ID:";
    private static final String ROLE_PREFIX    = "ROLE_";

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(RevocationRefreshGuard refreshGuard) {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            var authentication = context.getPrincipal();
            // userId e roles já estão nas authorities (carregados em AuthorizationService.loadUserByUsername).
            // USER_ID: é filtrado aqui — nunca entra no JWT como role ou permission.
            String userId = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith(USER_ID_PREFIX))
                .findFirst()
                .map(a -> a.substring(USER_ID_PREFIX.length()))
                .orElse(null);

            // Revogação ativa (ADR-017): num grant refresh_token, barra a reemissão se o titular foi
            // revogado/desativado depois da emissão do refresh token apresentado. Sem isso o gateway
            // renovaria silenciosamente o access token, perpetuando credenciais válidas. Fora do
            // refresh (authorization_code), o epoch é irrelevante — o login acabou de validar o estado.
            if (AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())) {
                OAuth2Authorization authorization = context.getAuthorization();
                Instant refreshIssuedAt = (authorization != null && authorization.getRefreshToken() != null)
                    ? authorization.getRefreshToken().getToken().getIssuedAt()
                    : null;
                if (refreshGuard.isRevoked(userId, refreshIssuedAt)) {
                    throw new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_grant", "O token foi revogado", null));
                }
            }
            List<String> roles = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith(ROLE_PREFIX))
                .map(a -> a.substring(ROLE_PREFIX.length()))
                .toList();
            // Permissions derivadas das roles reais do usuário (em vez de fixas para todos):
            // ADMIN ganha users.delete. LinkedHashSet deduplica (USER+ADMIN não repete
            // read/write) e mantém ordem estável.
            Set<String> permissions = new LinkedHashSet<>();
            for (String role : roles) {
                switch (role) {
                    case "USER"  -> permissions.addAll(List.of("users.read", "users.write"));
                    case "ADMIN" -> permissions.addAll(List.of("users.read", "users.write", "users.delete"));
                    default      -> { /* role desconhecida: sem permissions */ }
                }
            }
            context.getClaims().claims(claims -> {
                claims.put("userID", userId);
                // ArrayList (não List.of / não Stream.toList / não o Set direto): o
                // JdbcOAuth2AuthorizationService serializa os claims no Postgres com type-id,
                // e o PolymorphicTypeValidator do SAS rejeita java.util.ImmutableCollections$*
                // / Set$* na releitura (ex.: /userinfo).
                claims.put("roles", new ArrayList<>(roles));
                claims.put("permissions", new ArrayList<>(permissions));
                claims.put("scope", context.getAuthorizedScopes());
            });
        };
    }
}