package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.mock.web.MockHttpServletRequest;

import authorizationserver.filter.AuthorizationEndpointRevalidationFilter;

import jakarta.servlet.Filter;

/**
 * AC-34 — verificação <b>estrutural e positiva</b> do filtro de re-derivação na
 * {@link FilterChainProxy} real: um filtro corretamente escrito e incorretamente posicionado é um
 * <b>no-op com build verde</b> (mesma classe de falha do sampling de tracing documentado e inerte),
 * e nenhum teste de comportamento pega isso — se ele não roda, o resto do sistema se comporta
 * exatamente como antes do fix.
 *
 * <p>As três asserções são positivas, não contrafactuais: índice na chain, presença na
 * {@code @Order(1)} e ausência na {@code @Order(2)}.
 */
class AuthorizationChainStructureIntegrationTest extends AbstractAuthIntegrationTest {

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    @DisplayName("AC-34: o filtro está DEPOIS do SecurityContextHolderFilter na chain do authorize")
    void deveEstarDepoisDoSecurityContextHolderFilter() {
        List<Filter> filtros = chainDoAuthorizationEndpoint();

        int indiceContexto = indiceDe(filtros, SecurityContextHolderFilter.class);
        int indiceRevalidacao = indiceDe(filtros, AuthorizationEndpointRevalidationFilter.class);

        assertTrue(indiceContexto >= 0, "SecurityContextHolderFilter deve estar na chain");
        assertTrue(indiceRevalidacao >= 0,
                "AuthorizationEndpointRevalidationFilter deve estar na chain do authorization endpoint");
        assertTrue(indiceRevalidacao > indiceContexto,
                "o filtro precisa rodar DEPOIS do SecurityContextHolderFilter (índices: contexto="
                        + indiceContexto + ", revalidação=" + indiceRevalidacao
                        + "). Antes dele o SecurityContextHolder está vazio e o filtro vira no-op silencioso.");
    }

    @Test
    @DisplayName("AC-34: o filtro está na chain @Order(1) — a única que casa /oauth2/authorize")
    void deveEstarNaChainDoAuthorizationServer() {
        assertTrue(indiceDe(chainDoAuthorizationEndpoint(), AuthorizationEndpointRevalidationFilter.class) >= 0);
    }

    @Test
    @DisplayName("AC-34: o filtro NÃO está na chain @Order(2) (form login / demais rotas)")
    void deveNaoEstarNaChainPadrao() {
        // /login é servido pela @Order(2): o securityMatcher(endpointsMatcher) da @Order(1) não o casa.
        List<Filter> chainDoLogin = chainPara("GET", "/login");

        assertEquals(-1, indiceDe(chainDoLogin, AuthorizationEndpointRevalidationFilter.class),
                "na chain @Order(2) o filtro nunca veria /oauth2/authorize — presença ali indica wiring errado");
    }

    @Test
    @DisplayName("AC-34: as duas chains são distintas (guarda contra o teste passar por engano)")
    void deveHaverDuasChainsDistintas() {
        // Sem esta guarda, se um dia as duas chains colapsassem numa só, o teste de ausência acima
        // passaria a contradizer o de presença — e um deles falharia por motivo obscuro.
        assertFalse(chainDoAuthorizationEndpoint().equals(chainPara("GET", "/login")),
                "chain do authorization endpoint e chain do form login devem ser distintas");
    }

    private List<Filter> chainDoAuthorizationEndpoint() {
        return chainPara("GET", "/oauth2/authorize");
    }

    private List<Filter> chainPara(String metodo, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, path);
        request.setServletPath(path);
        for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
            if (chain.matches(request)) {
                return chain.getFilters();
            }
        }
        throw new IllegalStateException("nenhuma SecurityFilterChain casa " + metodo + " " + path);
    }

    private int indiceDe(List<Filter> filtros, Class<? extends Filter> tipo) {
        for (int i = 0; i < filtros.size(); i++) {
            if (tipo.isInstance(filtros.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
