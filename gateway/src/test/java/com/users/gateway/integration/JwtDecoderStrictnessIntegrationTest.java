package com.users.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.users.gateway.security.JwksTestSupport;
import com.users.gateway.security.RevocationTokenReader;

/**
 * AC-30 e AC-31 — o resource server do gateway continua <b>estrito</b> depois do ADR-025.
 *
 * <p><b>Este teste NÃO herda de {@code AbstractGatewayIntegrationTest}, e isso é o ponto.</b> Aquela
 * base declara {@code @MockitoBean ReactiveJwtDecoder} para evitar rede no startup — com o decoder
 * mockado, o cenário catastrófico do blocker B-1 (decoder leniente sequestrando o resource server via
 * {@code @ConditionalOnMissingBean}, fazendo o gateway aceitar <b>bearer expirado</b>) passaria
 * despercebido por toda a suíte. Aqui o decoder é o de <b>produção</b>, alimentado por um
 * {@code jwk-set-uri} real servido por WireMock.
 *
 * <p>Registre também por que as duas asserções andam juntas: "existe exatamente UM bean de
 * {@code ReactiveJwtDecoder}" <b>passaria</b> no cenário exato que B-1 descreve — o leniente sendo
 * esse único bean. Quem fecha é a asserção <b>comportamental</b>: esse único bean rejeita {@code exp}
 * no passado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtDecoderStrictnessIntegrationTest {

    private static final WireMockServer wiremock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final JwksTestSupport jwks = new JwksTestSupport();

    static {
        wiremock.start();
        jwks.publicarJwks(wiremock);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Reusa o Redis estático da base de integração (sessão + rate limit) sem herdar seu
        // @MockitoBean do decoder.
        registry.add("spring.data.redis.host", AbstractGatewayIntegrationTest.redis::getHost);
        registry.add("spring.data.redis.port", () -> AbstractGatewayIntegrationTest.redis.getMappedPort(6379));
        // Decoder de PRODUÇÃO com JWKS alcançável: é isto que substitui o mock (restrição 5).
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> jwks.jwkSetUri(wiremock));
        registry.add("security.revocation.jwk-set-uri", () -> jwks.jwkSetUri(wiremock));
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                wiremock::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.authorization-server[0].uri",
                wiremock::baseUrl);
    }

    @Test
    @DisplayName("AC-30: bearer JWT RS256 real com exp NO PASSADO recebe 401")
    void deveRejeitarBearerExpirado() {
        String expirado = jwks.tokenAssinado("user-1",
                Instant.now().minus(30, ChronoUnit.MINUTES),
                Instant.now().minus(25, ChronoUnit.MINUTES));

        client().get().uri("/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expirado)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Controle: bearer válido NÃO é rejeitado — o 401 acima vem do exp, não do setup")
    void deveAceitarBearerValido() {
        // Sem este controle, um erro de configuração do JWKS faria o teste acima passar pelo motivo
        // errado (tudo 401) e a garantia seria vazia.
        wiremock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/users/me"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("{}")));
        String valido = jwks.tokenAssinado("user-1",
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES));

        client().get().uri("/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + valido)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("AC-31: existe EXATAMENTE UM bean de ReactiveJwtDecoder (o da autoconfig)")
    void deveHaverExatamenteUmReactiveJwtDecoder() {
        String[] beans = applicationContext.getBeanNamesForType(ReactiveJwtDecoder.class);

        assertThat(beans)
                .as("dois beans tornariam ambígua a injeção; zero significaria autoconfig desligada")
                .hasSize(1);
    }

    @Test
    @DisplayName("AC-31: o RevocationTokenReader existe e NÃO é um ReactiveJwtDecoder")
    void deveTerLeitorDeRevocacaoForaDaHierarquiaDoDecoder() {
        RevocationTokenReader reader = applicationContext.getBean(RevocationTokenReader.class);

        assertThat(reader).isNotNull();
        assertThat(applicationContext.getBeanNamesForType(ReactiveJwtDecoder.class))
                .as("o leitor leniente não pode aparecer entre os beans de ReactiveJwtDecoder")
                .noneMatch(nome -> applicationContext.getBean(nome) == reader);
    }
}
