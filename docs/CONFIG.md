# Variáveis de Ambiente

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler esta referência](#como-ler-esta-referência)
- [Docker secrets (base secrets-native)](#docker-secrets-base-secrets-native)
- [Infraestrutura e descoberta](#infraestrutura-e-descoberta)
- [Dados (Mongo · Postgres · Redis)](#dados-mongo--postgres--redis)
- [OAuth2 e JWT](#oauth2-e-jwt)
- [Canal interno](#canal-interno)
- [Consentimento LGPD](#consentimento-lgpd)
- [Verificação de e-mail (ADR-015)](#verificação-de-e-mail-adr-015)
- [Swagger / OpenAPI](#swagger--openapi)
- [CORS](#cors)
- [Borda e IP do cliente (lockout / rate limit)](#borda-e-ip-do-cliente-lockout--rate-limit)
- [Observabilidade](#observabilidade)
- [Front-end](#front-end)
- [Containers de infraestrutura (só `docker-compose`)](#containers-de-infraestrutura-só-docker-compose)
- [Limites de recursos (CPU / memória)](#limites-de-recursos-cpu--memória)

## Como ler esta referência

- **Default dev** é o valor embutido (no `*.yml` do config-server ou em `@Value` no código) quando a variável não é exportada.
- Variáveis **sem default** são _fail-fast_: o app não sobe sem elas (o `docker-compose.yml` injeta um valor de dev).
- A coluna **Serviço(s)** indica quem consome a variável.
- **Compose base prod-safe + override de dev:** `docker-compose.yml` publica só a borda (`gateway:8081` e `interface`); `docker-compose.override.yml` (auto-carregado por `docker compose up`) republica as portas internas em dev. As URLs voltadas ao **browser** (front-channel/redirects) entram no compose como `${VAR:-localhost-default}` — em dev usam o default; em **prod** (`docker compose -f docker-compose.yml up`) um `.env` as sobrescreve com os hostnames públicos. Ver `.env.example`.
- **Secrets sem default no config-server:** `AUTH_DB_USER`, `AUTH_DB_PASSWORD` e `OAUTH_CLIENT_SECRET` deixaram de ter default nos YAMLs servidos → ausência da env no cliente derruba a subida (mesma filosofia do `INTERNAL_API_TOKEN`).
- **Segredos vêm de Docker secrets, não do `.env`:** os valores sensíveis são injetados por arquivos em `/run/secrets/` (base secrets-native) — ver [§ Docker secrets](#docker-secrets-base-secrets-native). Onde as tabelas abaixo dizem "o compose injeta `<valor-dev>`", o valor vem do secret correspondente gerado por `infra/secrets/gen-secrets.sh`.
- **Config-server com Basic auth:** o endpoint do config-server exige autenticação (só `/actuator/health` fica aberto, para os healthchecks). O par `CONFIG_SERVER_USERNAME`/`CONFIG_SERVER_PASSWORD` vale para o usuário in-memory do config-server (`spring.security.user.*`) **e** para os clientes (`spring.cloud.config.username`/`password`).

## Docker secrets (base secrets-native)

A base `docker-compose.yml` é **secrets-native** (gap 0.3 RELATORIOA, [ADR-009](adr/ADR-009-base-secrets-native-docker-secrets.md)): os segredos **não** ficam no `.env`, mas em arquivos sob `./secrets/` (gitignorado), montados em `/run/secrets/<NOME>`. Gere uma vez antes do `docker compose up` (o `up` **falha** sem eles):

```bash
infra/secrets/gen-secrets.sh        # defaults de DEV
REDIS_PASSWORD=$(openssl rand -hex 32) OAUTH_CLIENT_SECRET=... infra/secrets/gen-secrets.sh   # prod
```

| Arquivo em `./secrets/` | Consumidor(es) | Mecanismo de leitura |
| --- | --- | --- |
| `CONFIG_SERVER_PASSWORD` | serviços Spring · prometheus | `configtree:/run/secrets/` · `basic_auth.password_file` |
| `OAUTH_CLIENT_SECRET` | gateway · auth-server | `configtree:/run/secrets/` |
| `INTERNAL_API_TOKEN` | user-service · auth-server · notification-service | `configtree:/run/secrets/` |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | notification-service | `configtree:/run/secrets/` |
| `REDIS_PASSWORD` | redis-1/2/3 · sentinels · serviços Spring | `$(cat ...)` (runtime) · `configtree:/run/secrets/` |
| `redis_exporter_json` | redis-exporter | `--redis.password-file` (JSON `{target: senha}`, não a senha crua) |
| `POSTGRES_PASSWORD` | postgres · postgres-exporter | `POSTGRES_PASSWORD_FILE` (nativo) · `$(cat ...)` |
| `MONGO_PASSWORD` | mongo | `MONGO_INITDB_ROOT_PASSWORD_FILE` (nativo) |
| `MONGODB_URI` | user-service | `configtree:/run/secrets/` |
| `jwk_private` / `jwk_public` | authorization-server | `JWK_PRIVATE_KEY=file:/run/secrets/jwk_private` (gerado por `infra/jwk/gen-keys.sh`) |
| `GRAFANA_ADMIN_PASSWORD` | grafana | `GF_SECURITY_ADMIN_PASSWORD__FILE` |

> **No `configtree`, o nome do arquivo é o *placeholder* da property** (ex.: `OAUTH_CLIENT_SECRET` → `${OAUTH_CLIENT_SECRET}`). Arquivos gerados com `printf '%s'` (sem newline final) e `chmod 644` (consumidores rodam não-root; em Compose não-Swarm o modo do host é preservado) — ver ADR-009.
>
> **Resíduo 0.3:** o `mongodb-exporter` (imagem distroless) **não** lê secret — continua usando `MONGO_USER`/`MONGO_PASSWORD` do `.env`, que devem casar com `./secrets/MONGO_PASSWORD` (ver [SECURITY.md](SECURITY.md)).

## Infraestrutura e descoberta

| Variável            | Serviço(s)             | Default dev                     | Observação                                                            |
| ------------------- | ---------------------- | ------------------------------- | -------------------------------------------------------------------- |
| `SERVER_PORT`       | todos                  | por serviço¹                    | Porta HTTP do serviço.                                                |
| `CONFIG_SERVER_URL` | todos (exceto config)  | —                               | `spring.config.import=optional:configserver:...`. Compose: `http://config-lb:8888` (nginx LB na frente de `config-server-1` e `config-server-2`). |
| `CONFIG_SERVER_USERNAME` | config-server · clientes | `config-client`             | Usuário do Basic auth do config-server. No config-server vira `spring.security.user.name`; nos clientes, `spring.cloud.config.username`. |
| `CONFIG_SERVER_PASSWORD` | config-server · clientes | `config-dev-secret`         | Senha do Basic auth. **Cuidado:** o compose passa `${CONFIG_SERVER_PASSWORD}` **sem `:-default`** → se faltar no `.env`, o container recebe a var vazia (não cai no default do `application.yml`) e o config-server **falha na subida**. O `.env.example` lista ambas. |
| `EUREKA_URI`        | todos (exceto discovery) | `http://localhost:9091/eureka` | `defaultZone` do Eureka. Compose: lista CSV com ambas as instâncias HA (`http://discovery-server-1:9091/eureka,http://discovery-server-2:9092/eureka`). |
| `EUREKA_PEER_URL`   | discovery-server       | `http://localhost:9091/eureka`  | URL do **peer** Eureka (a outra instância). Cada nó aponta para o outro. |
| `EUREKA_HOSTNAME`   | discovery-server       | `localhost`                     | Hostname que a instância anuncia ao peer. Compose: `discovery-server-1` / `discovery-server-2`. |

> ¹ Defaults de porta: gateway `8081`, authorization-server `8082`, user-service `8090`, discovery-server `9091`, config-server `8888`.

## Dados (Mongo · Postgres · Redis)

| Variável            | Serviço(s)                          | Default dev                                  | Observação                                                       |
| ------------------- | ----------------------------------- | -------------------------------------------- | ---------------------------------------------------------------- |
| `MONGODB_URI`           | user-service                        | — (obrigatória)      | Compose: URI de replica set `mongodb://user_service:...@mongo-1:27017,mongo-2:27017,mongo-3:27017/user-db?replicaSet=rs0&authSource=admin`. |
| `MONGODB_DATABASE`      | user-service                        | `user-db`            | Base de dados de usuários.                                                          |
| `REDIS_SENTINEL_MASTER` | user-service · gateway · auth-server | `mymaster`          | Nome do master monitorado pelos Sentinels. Substitui `REDIS_HOST`/`REDIS_PORT`.     |
| `REDIS_SENTINEL_NODES`  | user-service · gateway · auth-server | `redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379` | Lista CSV de endereços dos processos Sentinel. |
| `REDIS_PASSWORD`        | redis-1/2/3 · redis-sentinel-1/2/3 · user-service · gateway · auth-server · redis-exporter | — (fail-fast) | Senha uniforme dos 6 nós Redis (ADR-008). Data nodes: `--requirepass`/`--masterauth`; sentinels: injetada em runtime no `/tmp/sentinel.conf` (`requirepass` + `sentinel auth-pass`). Clientes Spring: `spring.data.redis.password` (data nodes) e `spring.data.redis.sentinel.password` (sentinels). `redis-exporter`: JSON multi-alvo no secret `redis_exporter_json`. **Docker secret** (`/run/secrets/REDIS_PASSWORD`), não env — data nodes/sentinels via `$(cat ...)`, clientes Spring via `configtree`. Gere com `openssl rand -hex 32` (ver [§ Docker secrets](#docker-secrets-base-secrets-native)). |
| `SESSION_TIMEOUT`       | gateway · auth-server               | `30m`                                        | TTL de inatividade da sessão em Redis (Spring Session). Default explícito 30m; aceita unidades de duração (`30m`, `2h`). |
| `AUTH_DB_URL`       | authorization-server                | `jdbc:postgresql://localhost:5432/authdb`    | Datasource do Postgres que guarda o estado OAuth.                |
| `AUTH_DB_USER`      | authorization-server                | — (fail-fast)                                | Sem default no config-server; o compose injeta `${POSTGRES_USER}`. |
| `AUTH_DB_PASSWORD`  | authorization-server                | — (fail-fast)                                | Sem default no config-server; o compose injeta `${POSTGRES_PASSWORD}`. |

## OAuth2 e JWT

| Variável                   | Serviço(s)            | Default dev                                          | Observação                                                                                  |
| -------------------------- | --------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `AUTH_ISSUER_URI`          | user-service · gateway | `http://localhost:8082`                              | `issuer-uri` para validação do JWT (resource server). Hostname **interno** (back-channel).  |
| `AUTH_ISSUER`              | authorization-server  | `http://localhost:8082`                              | Issuer que o próprio auth-server anuncia.                                                    |
| `JWK_PRIVATE_KEY`          | authorization-server  | `classpath:keys/app.key`                             | Chave privada RSA (PEM). A chave **não é mais versionada** (gap 0.1, [ADR-005](adr/ADR-005-chave-jwk-persistente.md)): gerada fora do repo por `infra/jwk/gen-keys.sh`. No compose secrets-native = `file:/run/secrets/jwk_private` (secret `jwk_private`). Em dev manual, `gen-keys.sh` escreve no classpath. |
| `JWK_PUBLIC_KEY`           | authorization-server  | `classpath:keys/app.pub`                             | Chave pública RSA (PEM). Idem — secret `jwk_public` no compose (`file:/run/secrets/jwk_public`). |
| `JWK_KEY_ID`               | authorization-server  | `user-service-key`                                   | `kid` estável da chave de assinatura.                                                        |
| `OAUTH_CLIENT_ID`          | gateway · auth-server | `gateway-client`                                     | Client confidencial do BFF.                                                                  |
| `OAUTH_CLIENT_SECRET`      | gateway · auth-server | — (fail-fast)                                        | Sem default no config-server; injetado via Docker secret (`configtree:/run/secrets/`). Sobrescrever em produção. |
| `OAUTH_REDIRECT_URI`       | gateway               | `{baseUrl}/login/oauth2/code/gateway-client`         | `redirect-uri` do fluxo. Em **dev manual** (`npm run dev`), exporte `http://localhost:5173/login/oauth2/code/gateway-client`.² |
| `OAUTH_AUTHORIZATION_URI`  | gateway               | `http://localhost:8082/oauth2/authorize`             | Endpoint de autorização **front-channel** (browser). Só este endpoint usa hostname externo. |
| `OAUTH_END_SESSION_URI`    | gateway               | `http://localhost:8082/connect/logout`               | Endpoint de logout OIDC do IdP (browser). Lido em `gateway/.../SecurityConfig.java`.        |
| `POST_LOGOUT_REDIRECT_URI` | gateway               | `http://localhost:5173/`                             | Para onde o IdP devolve o browser após o logout. Deve bater com a `postLogoutRedirectUri` registrada no `RegisteredClient`. |
| `OAUTH_CLIENT_REDIRECT_URIS` | authorization-server | `http://localhost:8081/login/oauth2/code/gateway-client,http://localhost:5173/login/oauth2/code/gateway-client,http://localhost:8081/swagger-ui/oauth2-redirect.html,https://oauth.pstmn.io/v1/callback` | CSV dos `redirectUri` do `gateway-client` semeado em `OAuth2ClientConfig` (`oauth.gateway-client.redirect-uris`). **Mudar em prod exige re-seed:** o seed do `RegisteredClient` é idempotente só no `findByClientId` inicial — não há reconciliação; alterar a env sem recriar/atualizar o client no Postgres não atualiza os redirect URIs já persistidos. |
| `OAUTH_CLIENT_POST_LOGOUT_URIS` | authorization-server | `http://localhost:5173/` | CSV dos `postLogoutRedirectUri` do `gateway-client` (`oauth.gateway-client.post-logout-uris`). Mesma ressalva de re-seed acima. |

> ² O `RegisteredClient` no authorization-server já permite a URI de `:5173`, para o callback aterrissar no SPA.

## Canal interno

| Variável             | Serviço(s)              | Default dev      | Observação                                                                                       |
| -------------------- | ----------------------- | ---------------- | ------------------------------------------------------------------------------------------------ |
| `INTERNAL_API_TOKEN` | user-service · auth-server · notification-service | — (fail-fast) | Shared secret do canal `/internal/**` (header `X-Internal-Token`). Sem default no config-server — o app **não sobe** sem ele; o compose injeta `internal-dev-token`. **Mesmo valor nos três serviços** (notification-service reaproveita o secret já existente, sem token dedicado — ADR-015). |

## Consentimento LGPD

| Variável         | Serviço(s)   | Default dev | Observação                                                                                                  |
| ---------------- | ------------ | ----------- | ----------------------------------------------------------------------------------------------------------- |
| `TERMS_VERSION`  | user-service | `v1`        | Versão dos termos/privacidade registrada no cadastro (`app.terms.version`, ADR-012). **Bump** quando a política mudar — permite exigir re-consentimento e prova qual versão cada titular aceitou. |

## Verificação de e-mail (ADR-015)

| Variável                              | Serviço(s)            | Default dev              | Observação                                                                                                  |
| -------------------------------------- | ---------------------- | ------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `EMAIL_VERIFICATION_TOKEN_TTL`         | user-service           | `15m`                     | TTL do token opaco de verificação (`app.verification.token-ttl`).                                            |
| `EMAIL_VERIFICATION_RESEND_MAX`        | user-service           | `3`                       | Limite de reenvios por conta-alvo na janela (`app.verification.resend-max-per-window`, Redis, complementar ao rate limit por IP do gateway). |
| `EMAIL_VERIFICATION_RESEND_WINDOW`     | user-service           | `1h`                      | Janela do limite acima (`app.verification.resend-window`).                                                   |
| `EMAIL_VERIFICATION_OUTBOX_RETENTION`  | user-service           | `30d`                     | Retenção do registro de outbox (`notificationOutbox.purgeAt`) após expiração/confirmação do token — TTL index do Mongo atua sobre `purgeAt`, não sobre `expiresAt` (preserva histórico de auditoria). |
| `EMAIL_VERIFICATION_GRACE_PERIOD`      | authorization-server   | `24h`                     | Janela de carência do gate de login (`security.email-verification.grace-period`) — login permitido mesmo com `emailVerified=false` dentro da janela desde `registrationDate`, evitando conta permanentemente inacessível se o e-mail nunca chegar. |
| `API_BASE_URL`                         | user-service           | `http://localhost:8081`  | Reaproveitada como base do link de confirmação (`app.verification.base-url`) — já documentada em [§ Swagger/OpenAPI](#swagger--openapi); deve apontar ao gateway (borda), não ao user-service interno. |
| `SERVER_PORT` (notification-service)   | notification-service   | `8095`                    | Porta do serviço — nunca publicada pelo gateway nem pelo compose base; só republicada em dev via `docker-compose.override.yml`. |
| `SMTP_HOST` / `SMTP_PORT`              | notification-service   | `localhost` / `1025`      | Host/porta do servidor SMTP (`spring.mail.host/port`). Defaults de dev compatíveis com um MailHog/Mailpit local — sem credenciais reais. |
| `SMTP_USERNAME` / `SMTP_PASSWORD`      | notification-service   | _(vazio)_                 | Credenciais SMTP — Docker secret, sem valor real por default (placeholder de dev). Exporte com credenciais reais antes de `gen-secrets.sh` em produção. |
| `SMTP_AUTH` / `SMTP_STARTTLS`          | notification-service   | `false` / `false`         | Flags `spring.mail.properties.mail.smtp.auth`/`starttls.enable` — habilite conforme exigência do provedor SMTP real. |
| `APP_MAIL_FROM`                        | notification-service   | `no-reply@users.local`    | Remetente exibido nos e-mails de verificação (`app.mail.from`).                                              |

## Swagger / OpenAPI

| Variável                     | Serviço(s)             | Default dev                                              | Observação                                            |
| ---------------------------- | ---------------------- | ------------------------------------------------------- | ----------------------------------------------------- |
| `AUTH_URL`                   | gateway · user-service | `http://localhost:8082/oauth2/authorize`                | Authorize URL exibida no Swagger (`OpenAPIConfig`).   |
| `AUTH_TOKEN`                 | gateway · user-service | `http://localhost:8082/oauth2/token`                    | Token URL exibida no Swagger (`OpenAPIConfig`).       |
| `API_BASE_URL`               | user-service           | `http://localhost:8081`                                 | Base URL anunciada no OpenAPI do user-service.        |
| `OAUTH2SWAGGER_REDIRECT_URL` | gateway                | `http://localhost:8081/swagger-ui/oauth2-redirect.html` | `oauth2-redirect-url` do Swagger UI (`gateway.yml`).³ |

> ³ Nome consumido pelo `gateway.yml` é `OAUTH2SWAGGER_REDIRECT_URL`. As chaves `OAUTH_GATEWAY_CLIENT` e `OAUTH_SWAGGER_REDIRECT_URL` que aparecem no `docker-compose.yml` **não são lidas** por nenhum serviço.
>
> **Deploy via Cloudflare Tunnel (`docker-compose.deploy.yml`):** o overlay define `API_BASE_URL` **e** `AUTH_URL` **no user-service** (`${TUNNEL_ORIGIN}` / `${TUNNEL_ORIGIN}/oauth2/authorize`) — sem isso o `servers[]` do doc OpenAPI cai no default `localhost:8081` e o "Try it out" do Swagger dispara para `localhost` (mixed content na página HTTPS do túnel). `AUTH_TOKEN` permanece no default (`localhost:8082`) → o botão **Authorize** redireciona mas o OAuth2 não fecha no quick tunnel (URL efêmera). Todas as envs de borda derivam de `${TUNNEL_ORIGIN}`.

## CORS

CORS na borda + configurável por ambiente. Cada serviço lê a property `cors.allowed-origins` da sua config servida, com `setAllowedOriginPatterns` (compatível com `allowCredentials`). O **user-service não tem CORS** (nunca recebe fetch cross-origin — só via gateway).

| Variável                    | Serviço(s)           | Default dev              | Observação                                                                                                   |
| --------------------------- | -------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------ |
| `CORS_ALLOWED_ORIGINS`      | gateway              | `http://localhost:5173`  | Origens (CSV) do **SPA** permitidas pela borda. Em prod, a origem pública real do SPA. Logada no startup do gateway. |
| `CORS_ALLOWED_ORIGINS_AUTH` | authorization-server | `http://localhost:8081`  | Origem (CSV) do **Swagger-UI** permitida no auth-server — o Swagger é cliente OAuth2 no browser e faz fetch cross-origin a `/oauth2/token`. Em prod, a origem pública do Swagger/borda. Compose usa `${VAR:-default}`. |

## Borda e IP do cliente (lockout / rate limit)

Fonte de IP do **lockout** (auth-server) e do **rate limiting** (gateway). Não-falsificável sob
Cloudflare Tunnel (ADR-010, item 1.2 RELATORIOA): o header confiável é a fonte primária; o
`forward-headers-strategy` é o fallback. Detalhe em [SECURITY.md](SECURITY.md).

| Variável                          | Serviço(s)            | Default dev       | Observação                                                                                                  |
| --------------------------------- | --------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------- |
| `SERVER_FORWARD_HEADERS_STRATEGY` | gateway · auth-server | `framework`       | Faz o `remoteAddress`/`getRemoteAddr()` refletir o `X-Forwarded-For` sanitizado pela borda (fallback do IP). Default `framework` na **base** do config-server (antes só no overlay de deploy); seguro sem proxy. |
| `TRUSTED_CLIENT_IP_HEADER`        | gateway · auth-server | `CF-Connecting-IP`| Header de IP confiável, **fonte primária** do particionamento por IP. A Cloudflare sobrescreve `CF-Connecting-IP`. **Esvaziar/trocar** em deploy não-Cloudflare (cai no XFF via forward-headers). |
| `LOCKOUT_MAX_ATTEMPTS`            | auth-server           | `5`               | Falhas (conta+IP) antes do lockout.                                                                          |
| `LOCKOUT_DURATION`                | auth-server           | `15m`             | Janela/TTL do lockout no Redis.                                                                              |

## Observabilidade

| Variável                                       | Serviço(s)                          | Compose                              | Observação                                  |
| ---------------------------------------------- | ----------------------------------- | ------------------------------------ | ------------------------------------------- |
| `MANAGEMENT_TRACING_EXPORT_ZIPKIN_ENDPOINT`    | user-service · gateway · auth-server | `http://zipkin:9411/api/v2/spans`    | Endpoint do Zipkin para exportar spans.     |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`      | user-service · gateway · auth-server | `1.0`                                | Probabilidade de sampling de tracing. Default dev `1.0` (100%) nos `*.yml` do config-server; em **prod** reduza para conter custo/volume (ex.: `0.1`). |
| `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SESSION`    | gateway                             | `DEBUG`                              | Toggle de debug (dev) da sessão.            |
| `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY_WEB` | gateway                           | `DEBUG`                              | Toggle de debug (dev) do Spring Security.   |

**Storage do Zipkin** (container `zipkin`, só `docker-compose`). O Zipkin roda **in-memory por
default** (`STORAGE_TYPE=mem`) — adequado a dev, mas os traces somem no restart. Para
persistência em prod, aponte-o para um **Elasticsearch externo** (o base não sobe um ES para
não pesar a stack local; backend é exercício do consumidor do blueprint). As chaves abaixo são
host vars com prefixo `ZIPKIN_` que o compose injeta nos nomes nativos do container
(`STORAGE_TYPE`/`ES_HOSTS`/`ES_USERNAME`/`ES_PASSWORD`):

| Variável               | Container | Default dev | Observação                                                        |
| ---------------------- | --------- | ----------- | ---------------------------------------------------------------- |
| `ZIPKIN_STORAGE_TYPE`  | `zipkin`  | `mem`       | `mem` (in-memory) ou `elasticsearch` em prod.                    |
| `ZIPKIN_ES_HOSTS`      | `zipkin`  | _vazio_     | URL(s) do Elasticsearch (ex.: `https://es.exemplo.com:9200`).    |
| `ZIPKIN_ES_USERNAME`   | `zipkin`  | _vazio_     | Usuário do ES (se autenticado).                                  |
| `ZIPKIN_ES_PASSWORD`   | `zipkin`  | _vazio_     | Senha do ES (se autenticado).                                    |

## Front-end

| Variável        | Serviço(s)      | Default dev | Observação                                                          |
| --------------- | --------------- | ----------- | ------------------------------------------------------------------- |
| `VITE_API_URL`  | login-interface | _vazio_     | Em dev/Docker fica vazio — chamadas relativas via proxy same-origin. |

## Containers de infraestrutura (só `docker-compose`)

Consumidas pelos próprios containers de banco na inicialização, não pelos serviços Spring:

| Variável                      | Container              | Compose         | Observação                                         |
| ----------------------------- | ---------------------- | --------------- | -------------------------------------------------- |
| `POSTGRES_DB`                 | `auth-postgres`        | `authdb`        |                                                    |
| `POSTGRES_USER`               | `auth-postgres`        | `auth_service`  |                                                    |
| `POSTGRES_PASSWORD`           | `auth-postgres`        | `auth_1234321`  |                                                    |
| `MONGO_INITDB_ROOT_USERNAME`  | `mongo-1`              | `user_service`  | Só no nó primário; secundários recebem por replicação. |
| `MONGO_INITDB_ROOT_PASSWORD`  | `mongo-1`              | `user_1234321`  |                                                    |
| `WEB_HOST_PORT`               | `interface`            | `5173`          | Porta do **host** da borda pública (mapeia para `:80` do container). Dev usa `5173`; prod tipicamente `80`. |

## Limites de recursos (CPU / memória)

Cada serviço do `docker-compose.yml` declara teto e reserva de recursos para que um serviço
com leak não consuma o host inteiro e derrube os vizinhos. Usamos as chaves de nível de
serviço **`cpus` / `mem_limit` / `mem_reservation`** (não o bloco `deploy.resources`):
`docker compose up` (v2) as aplica de forma determinística **fora do Swarm**, enquanto parte
de `deploy:` só vale em modo Swarm. Validar com `docker compose -f docker-compose.yml config`
(exit 0).

- **`cpus`** — teto de CPU (fração de núcleos). Limite, não reserva.
- **`mem_limit`** — teto de memória; o container é morto (OOMKilled) se ultrapassar.
- **`mem_reservation`** — alvo de memória usado pelo scheduler para distribuir os containers
  (soft); omitido nos serviços pequenos/efêmeros.

Os valores são **defaults de dev-blueprint**: `mem_limit` folgado para não causar OOMKill no
boot (a JVM 21 calibra o heap em ~25% do limite do container via `MaxRAMPercentage`). Ajuste
conforme a carga real do consumidor do blueprint.

| Classe                | Serviços                                            | `cpus` | `mem_limit` | `mem_reservation` |
| --------------------- | --------------------------------------------------- | ------ | ----------- | ----------------- |
| App JVM (pesado)      | `gateway`, `authorization-server`, `user-service`   | 1.0    | 1024m       | 512m              |
| App JVM (leve)        | `config-server-1/2`, `discovery-server-1/2`         | 0.75   | 512m        | 256m              |
| MongoDB               | `mongo-1/2/3`                                        | 1.0    | 1024m       | 512m              |
| PostgreSQL            | `auth-postgres`                                      | 0.75   | 512m        | 256m              |
| Redis (data)          | `redis-1/2/3`                                        | 0.5    | 256m        | 64m               |
| Redis Sentinel        | `redis-sentinel-1/2/3`                               | 0.25   | 128m        | —                 |
| Zipkin (in-memory)    | `zipkin`                                             | 0.5    | 512m        | 256m              |
| Prometheus            | `prometheus`                                         | 0.5    | 512m        | 256m              |
| Grafana               | `grafana`                                            | 0.5    | 256m        | 128m              |
| Exporters             | `mongodb-exporter`, `postgres-exporter`, `redis-exporter` | 0.25 | 128m    | —                 |
| Nginx                 | `config-lb`, `interface`                            | 0.25   | 64m         | —                 |
| One-shot              | `mongo-init`                                         | 0.5    | 256m        | —                 |

> A soma das **reservas** (~5,2 GB) é o que dimensiona o host; os `mem_limit` são tetos de
> contenção, não memória pré-alocada. O `docker-compose.override.yml` (dev) só republica
> portas — os limites do base valem em dev e no deploy prod-like.
