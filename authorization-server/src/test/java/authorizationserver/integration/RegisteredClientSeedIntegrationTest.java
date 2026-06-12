package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.util.ReflectionTestUtils;

import authorizationserver.config.OAuth2ClientConfig;

/**
 * Idempotência do seed do {@code gateway-client} (OAuth2ClientConfig): a primeira
 * inicialização do contexto já semeou o client no Postgres do Testcontainers; estes
 * testes re-executam a lógica REAL de seed ({@code findByClientId} → {@code save} se
 * ausente) simulando uma segunda subida do serviço, e verificam no banco que o registro
 * não é duplicado nem tem {@code redirectUris}/scopes corrompidos.
 *
 * <p>A re-execução instancia {@code OAuth2ClientConfig} fora do proxy de
 * {@code @Configuration} (chamar o método {@code @Bean} via bean Spring devolveria o
 * singleton cacheado sem executar o corpo); o {@code clientSecret} — injetado por
 * {@code @Value} em produção — é setado por reflexão com o mesmo valor do yml de teste.
 */
class RegisteredClientSeedIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String CLIENT_ID = "gateway-client";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${oauth.client.secret}")
    private String clientSecret;

    @Test
    void deveManterRegistroUnico_quandoSeedReexecutado() {
        // ARRANGE — contexto de pé: o seed da subida já criou o gateway-client
        int registrosAntes = contarRegistros();

        // ACT — segunda "inicialização" executa a mesma lógica de seed contra o mesmo banco
        reexecutarSeed();

        // ASSERT — nenhuma duplicação
        assertEquals(1, registrosAntes, "a subida do contexto deve ter semeado exatamente um registro");
        assertEquals(1, contarRegistros(), "reexecutar o seed não deve duplicar o gateway-client");
    }

    @Test
    void devePreservarRedirectUrisEScopes_quandoSeedReexecutado() {
        // ARRANGE/ACT
        reexecutarSeed();
        RegisteredClient cliente = new JdbcRegisteredClientRepository(jdbcTemplate).findByClientId(CLIENT_ID);

        // ASSERT — redirectUris (incluindo Swagger) e scopes intactos no Postgres
        assertNotNull(cliente, "o gateway-client deve continuar existindo após o re-seed");
        assertEquals(Set.of(
                "http://localhost:8081/login/oauth2/code/gateway-client",
                "http://localhost:5173/login/oauth2/code/gateway-client",
                "https://app.localhost/login/oauth2/code/gateway-client",
                "http://localhost:8081/swagger-ui/oauth2-redirect.html",
                "https://oauth.pstmn.io/v1/callback"), cliente.getRedirectUris());
        assertEquals(Set.of("openid", "profile", "users.read", "users.write"), cliente.getScopes());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Executa o corpo real de {@code registeredClientRepository(...)} (lógica de seed de produção). */
    private void reexecutarSeed() {
        OAuth2ClientConfig config = new OAuth2ClientConfig();
        ReflectionTestUtils.setField(config, "clientSecret", clientSecret);
        config.registeredClientRepository(jdbcTemplate);
    }

    private int contarRegistros() {
        Integer total = jdbcTemplate.queryForObject(
                "select count(*) from oauth2_registered_client where client_id = ?",
                Integer.class, CLIENT_ID);
        assertNotNull(total);
        return total;
    }
}
