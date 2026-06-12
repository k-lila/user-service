package com.users.gateway.integration;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Base dos testes de integração do gateway. Sobe o contexto reativo completo em porta
 * aleatória e provê:
 * - Redis real (Testcontainers) para rate limiting e sessão;
 * - um WireMock único representando ambos os downstream (user-service / authorization-server),
 *   resolvidos pelo SimpleDiscoveryClient + load balancer (lb://).
 *
 * O {@link ReactiveJwtDecoder} é mockado para o resource server não buscar JWKS no boot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractGatewayIntegrationTest {

    // Resource server JWT: evita qualquer chamada de rede (discovery/JWKS) no startup.
    @MockitoBean
    protected ReactiveJwtDecoder reactiveJwtDecoder;

    @Value("${local.server.port}")
    private int port;

    protected WebTestClient webTestClient;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    private ReactiveRedisConnectionFactory redisConnectionFactory;

    protected static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static final WireMockServer downstream =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        redis.start();
        downstream.start();
    }

    @BeforeEach
    void setupWebTestClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @BeforeEach
    @AfterEach
    void limparEstadoCompartilhado() {
        downstream.resetAll();
        // Rate limit é stateful no Redis; limpar evita contaminação de contadores entre testes.
        redisConnectionFactory.getReactiveConnection().serverCommands().flushAll().block();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // SimpleDiscoveryClient resolve os nomes lógicos do lb:// para o WireMock.
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                downstream::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.authorization-server[0].uri",
                downstream::baseUrl);
    }

    /** Atalho para o cliente WireMock estático compartilhado. */
    protected WireMock wireMock() {
        return new WireMock(downstream.port());
    }
}
