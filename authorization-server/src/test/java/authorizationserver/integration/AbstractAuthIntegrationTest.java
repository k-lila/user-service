package authorizationserver.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Base dos testes de integração do authorization-server (C10.1).
 *
 * <p>Infra real via Testcontainers: Postgres (estado OAuth — registered client,
 * authorizations, consents; schemas SAS aplicados via {@code spring.sql.init}) e
 * Redis (sessão Spring Session + contadores de lockout C19). O user-service é
 * substituído por WireMock — o módulo não acessa MongoDB, só o canal Feign
 * {@code GET /internal/users/email/{email}}; o Feign resolve o nome lógico
 * {@code user-service} para o WireMock via SimpleDiscoveryClient (Eureka desabilitado).
 *
 * <p>As propriedades estáticas de teste vivem em {@code src/test/resources/application.yml}
 * (que faz sombra ao application.yml de produção, sem import do config-server).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractAuthIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /** Dublê do user-service no canal interno Feign. */
    static final WireMockServer userServiceMock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        postgres.start();
        redis.start();
        userServiceMock.start();
    }

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void limparEstadoCompartilhado() {
        userServiceMock.resetAll();
        limparRedis();
        resetarCircuitBreakers();
    }

    @AfterEach
    void limparEstadoCompartilhadoApos() {
        userServiceMock.resetAll();
        limparRedis();
        resetarCircuitBreakers();
    }

    // O estado do circuit breaker (C7) vive no CircuitBreakerRegistry do contexto Spring,
    // compartilhado entre os testes: um circuito deixado aberto por um teste de resiliência
    // derrubaria os logins dos demais. Reset devolve todos ao estado CLOSED com métricas zeradas.
    private void resetarCircuitBreakers() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // Limpa sessões (Spring Session) e contadores de lockout (C19) entre testes,
    // evitando que falhas de login de um teste bloqueiem o par (conta, IP) de outro.
    private void limparRedis() {
        try (var conn = redisConnectionFactory.getConnection()) {
            conn.serverCommands().flushDb();
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Feign "user-service" → WireMock: instância fixa no SimpleDiscoveryClient
        // (com eureka.client.enabled=false o LoadBalancer resolve por aqui).
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                userServiceMock::baseUrl);
    }
}
