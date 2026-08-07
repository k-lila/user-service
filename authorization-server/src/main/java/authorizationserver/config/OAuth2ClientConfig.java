package authorizationserver.config;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.jdbc.core.JdbcTemplate;


@Configuration
public class OAuth2ClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2ClientConfig.class);

    @Value("${oauth.client.secret}")
    private String clientSecret;

    // Externalizados (config-server → authorization-server.yml) para que trocar o domínio
    // público (ex.: futuro named tunnel/Cloudflare) seja mudança de config, não de código.
    // Defaults inline preservam o comportamento de dev mesmo se a propriedade não chegar
    // a ser resolvida pelo config-server (ex.: testes que instanciam esta classe via `new`).
    @Value("${oauth.gateway-client.redirect-uris:"
            + "http://localhost:8081/login/oauth2/code/gateway-client,"
            + "http://localhost:5173/login/oauth2/code/gateway-client,"
            + "http://localhost:8081/swagger-ui/oauth2-redirect.html,"
            + "https://oauth.pstmn.io/v1/callback}")
    private List<String> gatewayClientRedirectUris;

    @Value("${oauth.gateway-client.post-logout-uris:"
            + "http://localhost:5173/}")
    private List<String> gatewayClientPostLogoutUris;

    @Bean
	PasswordEncoder passwordEncoder() {
	    return new BCryptPasswordEncoder();
	}

    // Estado OAuth persistido em JDBC (Postgres) para suportar N instâncias: o `code`
    // emitido por uma instância precisa ser trocável por token em qualquer outra.
    // Substitui InMemoryRegisteredClientRepository + os defaults InMemory de
    // OAuth2AuthorizationService/OAuth2AuthorizationConsentService.
    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcTemplate);
        seedGatewayClient(repository);
        return repository;
    }

    /**
     * Seeding idempotente do gateway-client (antes em memória). Preserva todos os
     * redirectUri/scopes — incluindo o redirect do Swagger UI.
     *
     * <p><b>Tolerante à corrida entre instâncias (ADR-022).</b> O par
     * {@code findByClientId} → {@code save} é um check-then-act: com N instâncias subindo
     * juntas, todas leem "ausente" e todas gravam. Quem fecha a brecha é o índice único sobre
     * {@code client_id} (ver {@code schema/oauth2-registered-client-schema.sql}); aqui apenas
     * absorvemos a violação resultante, para que o perdedor da corrida siga a subida em vez de
     * abortar o contexto. Sem a constraint este catch nunca dispararia e o banco acumularia
     * duplicatas silenciosas.
     *
     * <p>Package-private para o teste conseguir exercitar o caminho da duplicata com um
     * repositório dublê — o método {@code @Bean} constrói o repositório real internamente.
     */
    void seedGatewayClient(RegisteredClientRepository repository) {
        if (repository.findByClientId("gateway-client") != null) {
            return;
        }
        try {
            repository.save(gatewayClient());
        } catch (DuplicateKeyException | IllegalArgumentException e) {
            // A mesma corrida chega por duas exceções, conforme o momento em que a outra
            // instância commitou:
            //  - IllegalArgumentException — o assertUniqueIdentifiers do
            //    JdbcRegisteredClientRepository já enxerga a linha. Note que ele é, ele próprio,
            //    outro check-then-act: por isso NÃO substitui o índice único, só antecipa o erro.
            //  - DuplicateKeyException — as duas instâncias passaram por aquele check e foi o
            //    índice único que rejeitou o segundo INSERT.
            // A releitura confirma que é a corrida: se o client não estiver lá, a exceção tem
            // outra causa (um RegisteredClient malformado, p. ex.) e não pode ser engolida.
            if (repository.findByClientId("gateway-client") == null) {
                throw e;
            }
            LOGGER.info("| seed | gateway-client já semeado por outra instância | seguindo sem gravar");
        }
    }

    // Nome do bean distinto de 'authorizationService' (o UserDetailsService
    // authorizationserver.services.AuthorizationService já ocupa esse nome). Wiring é por tipo.
    @Bean
    public OAuth2AuthorizationService oauth2AuthorizationService(JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public OAuth2AuthorizationConsentService oauth2AuthorizationConsentService(JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    private RegisteredClient gatewayClient() {
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("gateway-client")
                .clientSecret(passwordEncoder().encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        // redirectUri/postLogoutRedirectUri vêm de oauth.gateway-client.* (config-server).
        // ATENÇÃO: o seed é idempotente (findByClientId → save só se ausente, ver
        // seedGatewayClient()) — alterar estas URIs em produção exige zerar/re-seedar
        // o gateway-client (drop + restart), não há reconciliação automática de cliente existente.
        gatewayClientRedirectUris.forEach(builder::redirectUri);
        gatewayClientPostLogoutUris.forEach(builder::postLogoutRedirectUri);
        return builder
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("users.read")
                .scope("users.write")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();
    }

}