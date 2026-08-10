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
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.users.gateway.security.JwksTestSupport;
import com.users.gateway.security.RevocationTokenReader;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Checagem de revogação na borda (ADR-017 + correção do {@code exp}, ADR-025).
 *
 * <p><b>O decoder aqui é REAL</b> — {@link RevocationTokenReader} sobre um JWKS servido por WireMock,
 * com tokens RS256 legítimos. A versão anterior deste teste mockava o {@code ReactiveJwtDecoder}, e é
 * por isso que o fail-open com token expirado (3 ocorrências em 35 min de uso normal) passou
 * despercebido: um mock que devolve um {@code Jwt} pronto nunca exercita a validação de {@code exp},
 * que era exatamente onde estava o defeito.
 */
@ExtendWith(MockitoExtension.class)
class RevocationWebFilterTest {

    @Mock private ServerOAuth2AuthorizedClientRepository authorizedClientRepository;
    @Mock private ReactiveStringRedisTemplate redis;
    @Mock private ReactiveValueOperations<String, String> valueOps;
    @Mock private OAuth2AuthorizedClient authorizedClient;
    @Mock private OAuth2AccessToken accessToken;

    private static final String PREFIX = "revoke:user:";

    private static final WireMockServer jwksServer =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final JwksTestSupport jwks = new JwksTestSupport();
    private static RevocationTokenReader tokenReader;

    private final AtomicBoolean chainCalled = new AtomicBoolean(false);
    private GatewayFilterChain chain;
    private ServerWebExchange exchange;

    @BeforeAll
    static void startJwks() {
        jwksServer.start();
        jwks.publicarJwks(jwksServer);
        tokenReader = new RevocationTokenReader(jwks.jwkSetUri(jwksServer));
    }

    @AfterAll
    static void stopJwks() {
        jwksServer.stop();
    }

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
        return new RevocationWebFilter(authorizedClientRepository, tokenReader, redis, enabled, PREFIX);
    }

    private void stubSessaoComToken(String token) {
        lenient().when(authorizedClientRepository.loadAuthorizedClient(any(), any(), any()))
                .thenReturn(Mono.just(authorizedClient));
        lenient().when(authorizedClient.getAccessToken()).thenReturn(accessToken);
        lenient().when(accessToken.getTokenValue()).thenReturn(token);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    private Mono<Void> invokeAutenticado() {
        Authentication auth = mock(OAuth2AuthenticationToken.class);
        return filter(true).filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    private String tokenValido(String userID, Instant emitidoEm) {
        return jwks.tokenAssinado(userID, emitidoEm, Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    private String tokenExpirado(String userID, Instant emitidoEm) {
        return jwks.tokenAssinado(userID, emitidoEm, Instant.now().minus(1, ChronoUnit.MINUTES));
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
        Instant emitidoEm = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        stubSessaoComToken(tokenValido("user-1", emitidoEm));
        when(valueOps.get(eq(PREFIX + "user-1")))
                .thenReturn(Mono.just(Long.toString(emitidoEm.toEpochMilli() - 60_000)));

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void deveSeguir_quandoSemMarcaDeRevogacao() {
        stubSessaoComToken(tokenValido("user-1", Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        when(valueOps.get(any())).thenReturn(Mono.empty());

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
    }

    @Test
    void deveRejeitarComUnauthorized_quandoTokenRevogado() {
        Instant emitidoEm = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        stubSessaoComToken(tokenValido("user-1", emitidoEm));
        when(valueOps.get(eq(PREFIX + "user-1")))
                .thenReturn(Mono.just(Long.toString(emitidoEm.toEpochMilli() + 60_000)));

        invokeAutenticado().block();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC-18: token EXPIRADO de titular revogado → 401 (antes, a checagem era pulada inteira)")
    void deveRejeitarComUnauthorized_quandoTokenExpiradoDeTitularRevogado() {
        // O caso do incidente: 18:08, 18:18, 18:43 — três vezes em 35 min o access token da sessão já
        // estava vencido, o decoder do resource server lançava JwtValidationException, o
        // onErrorResume engolia e o titular revogado seguia passando.
        Instant emitidoEm = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        stubSessaoComToken(tokenExpirado("user-revogado", emitidoEm));
        when(valueOps.get(eq(PREFIX + "user-revogado")))
                .thenReturn(Mono.just(Long.toString(emitidoEm.toEpochMilli() + 60_000)));

        invokeAutenticado().block();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC-18: a rejeição INVALIDA a sessão do gateway — 401 sozinho deixaria o SPA em laço")
    void deveInvalidarSessaoDoGateway_quandoTokenExpiradoDeTitularRevogado() {
        // O 401 sem invalidação seria pior que inofensivo: a sessão do gateway continuaria guardando
        // o OAuth2AuthorizedClient revogado, o SPA continuaria "logado" do seu ponto de vista e cada
        // request tomaria 401 de novo — o laço que o incidente produziu na UI, por outra porta. É a
        // invalidação que força o re-login, e o teste anterior (que assere só o status) passaria com
        // ela removida.
        Instant emitidoEm = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        stubSessaoComToken(tokenExpirado("user-revogado", emitidoEm));
        when(valueOps.get(eq(PREFIX + "user-revogado")))
                .thenReturn(Mono.just(Long.toString(emitidoEm.toEpochMilli() + 60_000)));
        WebSession sessao = exchange.getSession().block();
        sessao.getAttributes().put("marcador", "presente");

        invokeAutenticado().block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessao.getAttributes())
                .as("a sessão do gateway tem de ser invalidada junto do 401")
                .doesNotContainKey("marcador");
        assertThat(sessao.isStarted()).isFalse();
    }

    @Test
    @DisplayName("AC-20 [NEGATIVO]: token expirado de titular NÃO revogado segue o caminho normal")
    void deveSeguir_quandoTokenExpiradoENaoRevogado() {
        // A renovação silenciosa por refresh_token continua acontecendo como hoje: ignorar `exp` na
        // leitura NÃO é bloquear token expirado — é conseguir responder de quem ele é.
        Instant emitidoEm = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        stubSessaoComToken(tokenExpirado("user-ok", emitidoEm));
        when(valueOps.get(eq(PREFIX + "user-ok"))).thenReturn(Mono.empty());

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("AC-19 [NEGATIVO]: assinatura inválida → fail-open, sem tocar o Redis")
    void deveSeguir_emFailOpen_quandoAssinaturaInvalida() {
        stubSessaoComToken(jwks.tokenComAssinaturaInvalida("user-1",
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES)));

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("AC-19 [NEGATIVO]: token não-parseável → fail-open")
    void deveSeguir_emFailOpen_quandoTokenNaoParseavel() {
        stubSessaoComToken("isto-nao-e-um-jwt");

        invokeAutenticado().block();

        assertThat(chainCalled).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("AC-19 [NEGATIVO]: JWKS inalcançável → fail-open")
    void deveSeguir_emFailOpen_quandoJwksInalcancavel() {
        RevocationTokenReader leitorSemJwks =
                new RevocationTokenReader("http://localhost:1/oauth2/jwks");
        stubSessaoComToken(tokenValido("user-1", Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        Authentication auth = mock(OAuth2AuthenticationToken.class);

        new RevocationWebFilter(authorizedClientRepository, leitorSemJwks, redis, true, PREFIX)
                .filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(chainCalled).isTrue();
        verify(redis, never()).opsForValue();
    }
}
