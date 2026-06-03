package com.users.userservice.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.tracing.sampling.probability=0",
        "spring.data.mongodb.database=testdb",
        "internal.api.token=test-internal-token"
})
public abstract class AbstractIntegrationTest {

    // Impede que o OAuth2 Resource Server tente descobrir o emissor JWT no startup
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void limparCacheGlobal() {
        limparRedis();
    }

    @AfterEach
    void limparCacheGlobalApos() {
        limparRedis();
    }

    private void limparRedis() {
        try (var conn = redisConnectionFactory.getConnection()) {
            conn.serverCommands().flushDb();
        }
        var cacheById = cacheManager.getCache("usersById");
        if (cacheById != null) cacheById.clear();
        var cacheByEmail = cacheManager.getCache("usersByEmail");
        if (cacheByEmail != null) cacheByEmail.clear();
    }

    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        mongo.start();
        redis.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getConnectionString);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
