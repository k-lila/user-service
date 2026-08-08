package authorizationserver.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
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

    @Value("${oauth.gateway-client.redirect-uris}")
    private List<String> gatewayClientRedirectUris;

    @Value("${oauth.gateway-client.post-logout-uris}")
    private List<String> gatewayClientPostLogoutUris;

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
                "http://localhost:8081/swagger-ui/oauth2-redirect.html",
                "https://oauth.pstmn.io/v1/callback"), cliente.getRedirectUris());
        assertEquals(Set.of("openid", "profile", "users.read", "users.write"), cliente.getScopes());
    }

    @Test
    void deveAplicarRedirectUrisEPostLogoutUrisExternalizados_quandoPropriedadesDiferemDoDefault() {
        // ARRANGE — simula troca de domínio via env (oauth.gateway-client.*): o client ainda
        // não existe sob este client_id, então o seed (findByClientId == null → save) roda.
        OAuth2ClientConfig config = new OAuth2ClientConfig();
        ReflectionTestUtils.setField(config, "clientSecret", clientSecret);
        ReflectionTestUtils.setField(config, "gatewayClientRedirectUris",
                List.of("https://app.exemplo.com/login/oauth2/code/gateway-client"));
        ReflectionTestUtils.setField(config, "gatewayClientPostLogoutUris",
                List.of("https://app.exemplo.com/"));

        // ACT — chama o método de seed real com um client_id isolado, para não colidir com o
        // gateway-client já semeado na subida do contexto (com os defaults)
        RegisteredClient clienteCustomizado = ReflectionTestUtils.invokeMethod(config, "gatewayClient");
        assertNotNull(clienteCustomizado);
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcTemplate);
        repository.save(withClientId(clienteCustomizado, "gateway-client-custom-uri-test"));

        // ASSERT — as URIs aplicadas no RegisteredClient salvo são exatamente as externalizadas
        RegisteredClient persistido = repository.findByClientId("gateway-client-custom-uri-test");
        assertNotNull(persistido);
        assertEquals(Set.of("https://app.exemplo.com/login/oauth2/code/gateway-client"),
                persistido.getRedirectUris());
        assertEquals(Set.of("https://app.exemplo.com/"), persistido.getPostLogoutRedirectUris());
    }

    /**
     * O seed é um check-then-act ({@code findByClientId} → {@code save}): sob N instâncias subindo
     * juntas, todas leem "ausente" e todas gravam. Quem impede a duplicata é a constraint no banco
     * (ADR-022) — sem ela o banco aceitaria as N linhas em silêncio, cada uma com um hash BCrypt
     * distinto do mesmo segredo, e {@code findByClientId} passaria a devolver uma linha arbitrária
     * de uma query sem {@code ORDER BY}.
     */
    @Test
    void deveRejeitarSegundoRegistroComMesmoClientId() {
        // O INSERT é direto, e não via repository.save(), de propósito: o
        // JdbcRegisteredClientRepository tem seu próprio assertUniqueIdentifiers, que barraria a
        // gravação antes de o banco ser consultado — e que, sendo mais um check-then-act, é
        // exatamente o que a corrida atravessa. Só o INSERT cru reproduz o que duas instâncias
        // concorrentes de fato mandam ao Postgres.
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO oauth2_registered_client (
                    id, client_id, client_id_issued_at, client_secret, client_name,
                    client_authentication_methods, authorization_grant_types, redirect_uris,
                    post_logout_redirect_uris, scopes, client_settings, token_settings)
                SELECT gen_random_uuid()::text, client_id, client_id_issued_at, client_secret,
                    client_name, client_authentication_methods, authorization_grant_types,
                    redirect_uris, post_logout_redirect_uris, scopes, client_settings,
                    token_settings
                FROM oauth2_registered_client WHERE client_id = ?
                """, CLIENT_ID),
                "o índice único sobre client_id deve impedir a duplicata da corrida");

        assertEquals(1, contarRegistros(), "o gateway-client deve continuar único após a tentativa");
    }

    /**
     * A outra metade do par: perder a corrida não pode abortar a subida. A instância que grava
     * depois recebe a violação e apenas segue — o registro que interessa já está lá.
     */
    @Test
    void deveSeguirSemErro_quandoOutraInstanciaGravaEntreALeituraEAEscrita() {
        // ARRANGE — dublê que reproduz exatamente a janela da corrida: ausente na leitura do
        // seed, já presente quando o seed relê para confirmar a causa da violação.
        RegisteredClient jaSemeado = new JdbcRegisteredClientRepository(jdbcTemplate).findByClientId(CLIENT_ID);
        assertNotNull(jaSemeado);
        RegisteredClientRepository emCorrida = new RegisteredClientRepository() {
            private int leituras = 0;

            @Override
            public void save(RegisteredClient registeredClient) {
                throw new DuplicateKeyException("outra instância gravou primeiro");
            }

            @Override
            public RegisteredClient findById(String id) {
                return null;
            }

            @Override
            public RegisteredClient findByClientId(String clientId) {
                return leituras++ == 0 ? null : jaSemeado;
            }
        };

        // ACT/ASSERT — o seed absorve a violação em vez de derrubar o contexto
        assertDoesNotThrow(
                () -> ReflectionTestUtils.invokeMethod(
                        configComPropriedadesDeTeste(), "seedGatewayClient", emCorrida));
    }

    /**
     * O contraponto do teste acima: engolir a violação só é correto quando ela veio da corrida.
     * Se o client não está lá depois do erro, a causa é outra — um {@code RegisteredClient}
     * malformado, p. ex. — e mascará-la deixaria o serviço subir sem cliente OAuth nenhum.
     */
    @Test
    void devePropagarErro_quandoClientContinuaAusenteAposAViolacao() {
        RegisteredClientRepository sempreAusente = new RegisteredClientRepository() {
            @Override
            public void save(RegisteredClient registeredClient) {
                throw new IllegalArgumentException("client malformado");
            }

            @Override
            public RegisteredClient findById(String id) {
                return null;
            }

            @Override
            public RegisteredClient findByClientId(String clientId) {
                return null;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        configComPropriedadesDeTeste(), "seedGatewayClient", sempreAusente));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Executa o corpo real de {@code registeredClientRepository(...)} (lógica de seed de produção). */
    private void reexecutarSeed() {
        configComPropriedadesDeTeste().registeredClientRepository(jdbcTemplate);
    }

    /** {@code OAuth2ClientConfig} fora do proxy de {@code @Configuration}, com os @Value do yml de teste. */
    private OAuth2ClientConfig configComPropriedadesDeTeste() {
        OAuth2ClientConfig config = new OAuth2ClientConfig();
        ReflectionTestUtils.setField(config, "clientSecret", clientSecret);
        ReflectionTestUtils.setField(config, "gatewayClientRedirectUris", gatewayClientRedirectUris);
        ReflectionTestUtils.setField(config, "gatewayClientPostLogoutUris", gatewayClientPostLogoutUris);
        return config;
    }

    /** Clona o RegisteredClient sob um client_id distinto, evitando colidir com o gateway-client já semeado. */
    private RegisteredClient withClientId(RegisteredClient origem, String clientId) {
        return RegisteredClient.from(origem).id(java.util.UUID.randomUUID().toString()).clientId(clientId).build();
    }

    private int contarRegistros() {
        Integer total = jdbcTemplate.queryForObject(
                "select count(*) from oauth2_registered_client where client_id = ?",
                Integer.class, CLIENT_ID);
        assertNotNull(total);
        return total;
    }
}
