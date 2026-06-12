package com.users.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.session.WebSessionIdResolver;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unitários dos @Bean isolados do {@link SecurityConfig}: cookie de sessão, filtro que
 * materializa o cookie CSRF e o handler de logout OIDC (id_token_hint).
 */
class SecurityConfigBeansTest {

    private static final String END_SESSION = "http://localhost:8082/connect/logout";
    private static final String POST_LOGOUT = "http://localhost:5173/";

    // ----- webSessionIdResolver -----

    @Test
    void deveConfigurarCookieDeSessaoComHttpOnlySameSiteLaxESecure() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "cookieSecure", true);
        WebSessionIdResolver resolver = config.webSessionIdResolver();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        resolver.setSessionId(exchange, "session-xyz");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void deveDeixarCookieDeSessaoInseguro_quandoCookieSecureFalse() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "cookieSecure", false);
        WebSessionIdResolver resolver = config.webSessionIdResolver();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        resolver.setSessionId(exchange, "session-xyz");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isSecure()).isFalse();
    }

    // ----- csrfCookieWebFilter -----

    @Test
    void deveSubscreverCsrfToken_quandoAttributePresente() {
        SecurityConfig config = new SecurityConfig();
        WebFilter filter = config.csrfCookieWebFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        AtomicBoolean subscribed = new AtomicBoolean(false);
        Mono<CsrfToken> tokenMono = Mono
                .<CsrfToken>fromSupplier(() -> new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "tok"))
                .doOnSubscribe(s -> subscribed.set(true));
        exchange.getAttributes().put(CsrfToken.class.getName(), tokenMono);
        WebFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(subscribed).isTrue();
    }

    @Test
    void deveSeguirCadeia_quandoSemCsrfAttribute() {
        SecurityConfig config = new SecurityConfig();
        WebFilter filter = config.csrfCookieWebFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = e -> Mono.fromRunnable(() -> chainCalled.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    // ----- oidcLogoutSuccessHandler -----

    @Test
    void deveIncluirIdTokenHint_quandoPrincipalOidc() {
        ServerLogoutSuccessHandler handler =
                new SecurityConfig().oidcLogoutSuccessHandler(END_SESSION, POST_LOGOUT);
        OidcIdToken idToken = OidcIdToken.withTokenValue("the-id-token").claim("sub", "user").build();
        OidcUser oidcUser = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
        Authentication auth = new TestingAuthenticationToken(oidcUser, null);

        URI location = runLogout(handler, auth);

        assertThat(location.toString())
                .contains("id_token_hint=the-id-token")
                .contains("post_logout_redirect_uri=http://localhost:5173/");
        assertThat(location.getPath()).isEqualTo("/connect/logout");
    }

    @Test
    void deveUsarIdTokenHintVazio_quandoPrincipalNaoOidc() {
        ServerLogoutSuccessHandler handler =
                new SecurityConfig().oidcLogoutSuccessHandler(END_SESSION, POST_LOGOUT);
        Authentication auth = new TestingAuthenticationToken("plain-user", null);

        URI location = runLogout(handler, auth);

        assertThat(location.toString()).contains("id_token_hint=").doesNotContain("the-id-token");
        assertThat(location.getPath()).isEqualTo("/connect/logout");
    }

    @Test
    void deveUsarIdTokenHintVazio_quandoAutenticacaoNula() {
        ServerLogoutSuccessHandler handler =
                new SecurityConfig().oidcLogoutSuccessHandler(END_SESSION, POST_LOGOUT);

        URI location = runLogout(handler, null);

        assertThat(location.toString()).contains("id_token_hint=");
    }

    private URI runLogout(ServerLogoutSuccessHandler handler, Authentication auth) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout"));
        WebFilterExchange wfe = new WebFilterExchange(exchange, e -> Mono.empty());

        StepVerifier.create(handler.onLogoutSuccess(wfe, auth)).verifyComplete();

        return exchange.getResponse().getHeaders().getLocation();
    }
}
