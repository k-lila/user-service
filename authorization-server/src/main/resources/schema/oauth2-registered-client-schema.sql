-- Schema oficial do Spring Authorization Server 7.0.3 adaptado para PostgreSQL.
-- Adaptações (conforme cabeçalho do script oficial): timestamp -> timestamptz.
-- IF NOT EXISTS torna a inicialização idempotente entre restarts/instâncias.
CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamptz DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

-- Acréscimo ao schema oficial (ADR-022). A PK é `id` (UUID); o schema de origem não impõe
-- unicidade sobre `client_id`, e o seed do gateway-client em OAuth2ClientConfig é um
-- check-then-act (findByClientId → save). Com N instâncias subindo juntas, todas leem
-- ausente e todas gravam: N linhas com o mesmo client_id, cada uma com id e hash BCrypt
-- distintos. O login segue funcionando (todo hash valida o mesmo segredo), o que torna a
-- falha SILENCIOSA — e findByClientId passa a devolver `result.get(0)` de uma query sem
-- ORDER BY, isto é, uma linha arbitrária.
--
-- É a constraint que fecha a race; o catch de DuplicateKeyException no seed é só a metade
-- que a torna benigna (o perdedor segue em frente). Um catch sem esta linha seria apenas
-- mais uma checagem em corrida.
--
-- CREATE UNIQUE INDEX IF NOT EXISTS é idempotente E se aplica a tabela já existente —
-- diferente de CREATE TABLE IF NOT EXISTS, que não revisita o schema de uma tabela criada
-- antes desta mudança. Numa base que já tenha duplicatas a criação falha, e o
-- `continue-on-error: true` do spring.sql.init a engole: nesse caso, deduplicar à mão.
CREATE UNIQUE INDEX IF NOT EXISTS uk_oauth2_registered_client_client_id
    ON oauth2_registered_client (client_id);
