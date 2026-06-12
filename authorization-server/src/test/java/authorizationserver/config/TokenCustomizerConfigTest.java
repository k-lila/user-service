package authorizationserver.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@ExtendWith(MockitoExtension.class)
class TokenCustomizerConfigTest {

    private final OAuth2TokenCustomizer<JwtEncodingContext> customizer =
            new TokenCustomizerConfig().jwtCustomizer();

    /**
     * Monta o contexto mockado e devolve o mapa de claims que o customizer produziria.
     * Captura o {@code Consumer<Map>} passado a {@code claims().claims(...)} e o aplica
     * a um HashMap — evita as restrições de {@code JwtClaimsSet.build()}.
     */
    private Map<String, Object> runWith(String tokenType, Set<String> scopes, String... authorities) {
        JwtEncodingContext context = mock(JwtEncodingContext.class);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType(tokenType));

        Authentication principal = new TestingAuthenticationToken("user", "pass", authorities);
        // getPrincipal()/getAuthorizedScopes()/getClaims() só são consultados no ramo access_token.
        lenient().when(context.getPrincipal()).thenReturn(principal);
        lenient().when(context.getAuthorizedScopes()).thenReturn(scopes);

        JwtClaimsSet.Builder builder = mock(JwtClaimsSet.Builder.class);
        lenient().when(context.getClaims()).thenReturn(builder);

        customizer.customize(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Map<String, Object>>> captor = ArgumentCaptor.forClass(Consumer.class);
        Map<String, Object> claims = new HashMap<>();
        // Se o ramo de access_token não rodou, não há invocação a capturar.
        if (mockingDetails(builder).getInvocations().isEmpty()) {
            return claims;
        }
        verify(builder).claims(captor.capture());
        captor.getValue().accept(claims);
        return claims;
    }

    @Test
    void naoDeveCustomizar_quandoTokenNaoEhAccessToken() {
        JwtEncodingContext context = mock(JwtEncodingContext.class);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("id_token"));

        customizer.customize(context);

        // Retorna cedo: nunca toca principal/scopes/claims.
        verify(context, never()).getClaims();
        verify(context, never()).getPrincipal();
    }

    @Test
    void deveExtrairUserIdEPermissionsDeUser_quandoRoleUser() {
        Map<String, Object> claims =
                runWith("access_token", Set.of("openid"), "USER_ID:abc123", "ROLE_USER");

        assertEquals("abc123", claims.get("userID"));
        assertEquals(List.of("USER"), claims.get("roles"));
        assertEquals(List.of("users.read", "users.write"), claims.get("permissions"));
        assertEquals(Set.of("openid"), claims.get("scope"));
    }

    @Test
    void deveIncluirUsersDelete_quandoRoleAdmin() {
        Map<String, Object> claims =
                runWith("access_token", Set.of("openid"), "USER_ID:admin1", "ROLE_ADMIN");

        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) claims.get("permissions");
        assertEquals(List.of("users.read", "users.write", "users.delete"), permissions);
    }

    @Test
    void deveDeduplicarPermissions_quandoUserEAdmin() {
        Map<String, Object> claims =
                runWith("access_token", Set.of("openid"), "USER_ID:x", "ROLE_USER", "ROLE_ADMIN");

        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) claims.get("permissions");
        // LinkedHashSet deduplica read/write e mantém ordem estável.
        assertEquals(List.of("users.read", "users.write", "users.delete"), permissions);
    }

    @Test
    void deveDeixarUserIdNulo_quandoSemAuthorityUserId() {
        Map<String, Object> claims =
                runWith("access_token", Set.of("openid"), "ROLE_USER");

        assertNull(claims.get("userID"));
        assertEquals(List.of("USER"), claims.get("roles"));
    }

    @Test
    void naoDeveDerivarPermissions_quandoRoleDesconhecida() {
        Map<String, Object> claims =
                runWith("access_token", Set.of("openid"), "USER_ID:y", "ROLE_GUEST");

        assertEquals(List.of("GUEST"), claims.get("roles"));
        assertEquals(List.of(), claims.get("permissions"));
    }

    @Test
    void deveUsarArrayListMutavel_paraRolesEPermissions() {
        Map<String, Object> claims =
                runWith("access_token", Set.of("openid"), "USER_ID:z", "ROLE_USER");

        // O JdbcOAuth2AuthorizationService rejeita coleções imutáveis na releitura — invariante.
        assertInstanceOf(ArrayList.class, claims.get("roles"));
        assertInstanceOf(ArrayList.class, claims.get("permissions"));
    }
}
