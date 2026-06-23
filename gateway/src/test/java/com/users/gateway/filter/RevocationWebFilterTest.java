package com.users.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@ExtendWith(MockitoExtension.class)
class RevocationWebFilterTest {

    @Mock private ServerOAuth2AuthorizedClientRepository authorizedClientRepository;
    @Mock private ReactiveJwtDecoder jwtDecoder;
    @Mock private ReactiveStringRedisTemplate redis;
    @Mock private ReactiveValueOperations<String, String> valueOps;
    @Mock private OAuth2AuthorizedClient authorizedClient;
    @Mock private OAuth2AccessToken accessToken;

    private static final String PREFIX = "revoke:user:";
    private static final String TOKEN = "jwt-value";

    private final AtomicBoolean chainCalled = new AtomicBoolean(false);
    private GatewayFilterChain chain;
    private ServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        chainCalled.set(false);
        chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/users/me").build());
    }

    private RevocationWebFilter filter(boolean enabled) {
        return new RevocationWebFilter(authorizedClientRepository, jwtDecoder, redis, enabled, PREFIX);
    }

    private void stubAuthorizedClientWithToken(String userID, Instant issuedAt) {
        lenient().when(authorizedClientRepository.loadAuthorizedClient(any(), any(), any()))
                .thenReturn(Mono.just(authorizedClient));
        lenient().when(authorizedClient.getAccessToken()).thenReturn(accessToken);
        lenient().when(accessToken.getTokenValue()).thenReturn(TOKEN);
        Jwt jwt = Jwt.withTokenValue(TOKEN).header("alg", "none")
                .claim("userID", userID).issuedAt(issuedAt).build();
        lenient().when(jwtDecoder.decode(TOKEN)).thenReturn(Mono.just(jwt));
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    private Mono<Void> invokeAutenticado() {
        Authentication auth = mock(OAuth2AuthenticationToken.class);
        return filter(true).filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    @Test
    void deveSeguir_quandoDesabilitado() {
        filter(false).filter(exchange, chain).block();
        assertThat(chainCalled).isTrue();
    }

    @Test
    void deveSeguir_quandoSemAutenticacao() {
        // Sem contexto de segurança (rota permitAll): segue sem tocar Redis/decoder.
        filter(true).filter(exchange, chain).contextWrite(Context.empty()).block();
        assertThat(chainCalled).isTrue();
    }

    @Test
    void deveSeguir_quandoTokenNaoRevogado() {
        stubAuthorizedClientWithToken("user-1", Instant.ofEpochMilli(1_700_000_060_000L));
        when(valueOps.get(eq(PREFIX + "user-1"))).thenReturn(Mono.just("1700000000000"));

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void deveSeguir_quandoSemMarcaDeRevogacao() {
        stubAuthorizedClientWithToken("user-1", Instant.ofEpochMilli(1_700_000_000_000L));
        when(valueOps.get(any())).thenReturn(Mono.empty());

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
    }

    @Test
    void deveRejeitarComUnauthorized_quandoTokenRevogado() {
        stubAuthorizedClientWithToken("user-1", Instant.ofEpochMilli(1_700_000_000_000L));
        when(valueOps.get(eq(PREFIX + "user-1"))).thenReturn(Mono.just("1700000060000"));

        invokeAutenticado().block();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deveSeguir_emFailOpen_quandoDecoderFalha() {
        lenient().when(authorizedClientRepository.loadAuthorizedClient(any(), any(), any()))
                .thenReturn(Mono.just(authorizedClient));
        when(authorizedClient.getAccessToken()).thenReturn(accessToken);
        when(accessToken.getTokenValue()).thenReturn(TOKEN);
        when(jwtDecoder.decode(TOKEN)).thenReturn(Mono.error(new RuntimeException("decode boom")));

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
        verify(redis, never()).opsForValue();
    }
}
