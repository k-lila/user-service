-- Schema oficial do Spring Authorization Server 7.0.3 adaptado para PostgreSQL.
-- IF NOT EXISTS torna a inicialização idempotente entre restarts/instâncias.
CREATE TABLE IF NOT EXISTS oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
