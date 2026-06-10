# Variáveis de Ambiente

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler esta referência](#como-ler-esta-referência)
- [Infraestrutura e descoberta](#infraestrutura-e-descoberta)
- [Dados (Mongo · Postgres · Redis)](#dados-mongo--postgres--redis)
- [OAuth2 e JWT](#oauth2-e-jwt)
- [Canal interno](#canal-interno)
- [Swagger / OpenAPI](#swagger--openapi)
- [CORS](#cors)
- [Observabilidade](#observabilidade)
- [Front-end](#front-end)
- [Containers de infraestrutura (só `docker-compose`)](#containers-de-infraestrutura-só-docker-compose)

## Como ler esta referência

- **Default dev** é o valor embutido (no `*.yml` do config-server ou em `@Value` no código) quando a variável não é exportada.
- Variáveis **sem default** são _fail-fast_: o app não sobe sem elas (o `docker-compose.yml` injeta um valor de dev).
- A coluna **Serviço(s)** indica quem consome a variável.
- **Compose base prod-safe + override de dev (C16):** `docker-compose.yml` publica só a borda (`gateway:8081` e `interface`); `docker-compose.override.yml` (auto-carregado por `docker compose up`) republica as portas internas em dev. As URLs voltadas ao **browser** (front-channel/redirects) entram no compose como `${VAR:-localhost-default}` — em dev usam o default; em **prod** (`docker compose -f docker-compose.yml up`) um `.env` as sobrescreve com os hostnames públicos. Ver `.env.example`.
- **Secrets sem default no config-server (C17):** `AUTH_DB_USER`, `AUTH_DB_PASSWORD` e `OAUTH_CLIENT_SECRET` deixaram de ter default nos YAMLs servidos → ausência da env no cliente derruba a subida (mesma filosofia do `INTERNAL_API_TOKEN`).
- **Config-server com Basic auth (C17):** o endpoint do config-server exige autenticação (só `/actuator/health` fica aberto, para os healthchecks). O par `CONFIG_SERVER_USERNAME`/`CONFIG_SERVER_PASSWORD` vale para o usuário in-memory do config-server (`spring.security.user.*`) **e** para os clientes (`spring.cloud.config.username`/`password`).

## Infraestrutura e descoberta

| Variável            | Serviço(s)             | Default dev                     | Observação                                                            |
| ------------------- | ---------------------- | ------------------------------- | -------------------------------------------------------------------- |
| `SERVER_PORT`       | todos                  | por serviço¹                    | Porta HTTP do serviço.                                                |
| `CONFIG_SERVER_URL` | todos (exceto config)  | —                               | `spring.config.import=optional:configserver:...`. Compose: `http://config-lb:8888` (nginx LB na frente de `config-server-1` e `config-server-2`). |
| `CONFIG_SERVER_USERNAME` | config-server · clientes | `config-client`             | Usuário do Basic auth do config-server (C17). No config-server vira `spring.security.user.name`; nos clientes, `spring.cloud.config.username`. |
| `CONFIG_SERVER_PASSWORD` | config-server · clientes | `config-dev-secret`         | Senha do Basic auth (C17). **Cuidado:** o compose passa `${CONFIG_SERVER_PASSWORD}` **sem `:-default`** → se faltar no `.env`, o container recebe a var vazia (não cai no default do `application.yml`) e o config-server **falha na subida**. O `.env.example` lista ambas. |
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
| `AUTH_DB_URL`       | authorization-server                | `jdbc:postgresql://localhost:5432/authdb`    | Datasource do Postgres que guarda o estado OAuth.                |
| `AUTH_DB_USER`      | authorization-server                | — (fail-fast)                                | Sem default no config-server (C17); o compose injeta `${POSTGRES_USER}`. |
| `AUTH_DB_PASSWORD`  | authorization-server                | — (fail-fast)                                | Sem default no config-server (C17); o compose injeta `${POSTGRES_PASSWORD}`. |

## OAuth2 e JWT

| Variável                   | Serviço(s)            | Default dev                                          | Observação                                                                                  |
| -------------------------- | --------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `AUTH_ISSUER_URI`          | user-service · gateway | `http://localhost:8082`                              | `issuer-uri` para validação do JWT (resource server). Hostname **interno** (back-channel).  |
| `AUTH_ISSUER`              | authorization-server  | `http://localhost:8082`                              | Issuer que o próprio auth-server anuncia.                                                    |
| `JWK_PRIVATE_KEY`          | authorization-server  | `classpath:keys/app.key`                             | Chave privada RSA (PEM). **Default dev** — em prod usar secret montado (`file:/run/secrets/...`). |
| `JWK_PUBLIC_KEY`           | authorization-server  | `classpath:keys/app.pub`                             | Chave pública RSA (PEM). **Default dev**.                                                    |
| `JWK_KEY_ID`               | authorization-server  | `user-service-key`                                   | `kid` estável da chave de assinatura.                                                        |
| `OAUTH_CLIENT_ID`          | gateway · auth-server | `gateway-client`                                     | Client confidencial do BFF.                                                                  |
| `OAUTH_CLIENT_SECRET`      | gateway · auth-server | — (fail-fast)                                        | Sem default no config-server (C17); o compose injeta via `.env`. Sobrescrever em produção.   |
| `OAUTH_REDIRECT_URI`       | gateway               | `{baseUrl}/login/oauth2/code/gateway-client`         | `redirect-uri` do fluxo. Em **dev manual** (`npm run dev`), exporte `http://localhost:5173/login/oauth2/code/gateway-client`.² |
| `OAUTH_AUTHORIZATION_URI`  | gateway               | `http://localhost:8082/oauth2/authorize`             | Endpoint de autorização **front-channel** (browser). Só este endpoint usa hostname externo. |
| `OAUTH_END_SESSION_URI`    | gateway               | `http://localhost:8082/connect/logout`               | Endpoint de logout OIDC do IdP (browser). Lido em `gateway/.../SecurityConfig.java`.        |
| `POST_LOGOUT_REDIRECT_URI` | gateway               | `http://localhost:5173/`                             | Para onde o IdP devolve o browser após o logout. Deve bater com a `postLogoutRedirectUri` registrada no `RegisteredClient`. |

> ² O `RegisteredClient` no authorization-server já permite a URI de `:5173`, para o callback aterrissar no SPA.

## Canal interno

| Variável             | Serviço(s)              | Default dev      | Observação                                                                                       |
| -------------------- | ----------------------- | ---------------- | ------------------------------------------------------------------------------------------------ |
| `INTERNAL_API_TOKEN` | user-service · auth-server | — (fail-fast) | Shared secret do canal `/internal/**` (header `X-Internal-Token`). Sem default no config-server — o app **não sobe** sem ele; o compose injeta `internal-dev-token`. **Mesmo valor nos dois serviços.** |

## Swagger / OpenAPI

| Variável                     | Serviço(s)             | Default dev                                              | Observação                                            |
| ---------------------------- | ---------------------- | ------------------------------------------------------- | ----------------------------------------------------- |
| `AUTH_URL`                   | gateway · user-service | `http://localhost:8082/oauth2/authorize`                | Authorize URL exibida no Swagger (`OpenAPIConfig`).   |
| `AUTH_TOKEN`                 | gateway · user-service | `http://localhost:8082/oauth2/token`                    | Token URL exibida no Swagger (`OpenAPIConfig`).       |
| `API_BASE_URL`               | user-service           | `http://localhost:8081`                                 | Base URL anunciada no OpenAPI do user-service.        |
| `OAUTH2SWAGGER_REDIRECT_URL` | gateway                | `http://localhost:8081/swagger-ui/oauth2-redirect.html` | `oauth2-redirect-url` do Swagger UI (`gateway.yml`).³ |

> ³ Nome consumido pelo `gateway.yml` é `OAUTH2SWAGGER_REDIRECT_URL`. As chaves `OAUTH_GATEWAY_CLIENT` e `OAUTH_SWAGGER_REDIRECT_URL` que aparecem no `docker-compose.yml` **não são lidas** por nenhum serviço.

## CORS

CORS na borda + configurável por ambiente (C12). Cada serviço lê a property `cors.allowed-origins` da sua config servida, com `setAllowedOriginPatterns` (compatível com `allowCredentials`). O **user-service não tem CORS** (nunca recebe fetch cross-origin — só via gateway).

| Variável                    | Serviço(s)           | Default dev              | Observação                                                                                                   |
| --------------------------- | -------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------ |
| `CORS_ALLOWED_ORIGINS`      | gateway              | `http://localhost:5173`  | Origens (CSV) do **SPA** permitidas pela borda. Em prod, a origem pública real do SPA. Logada no startup do gateway. |
| `CORS_ALLOWED_ORIGINS_AUTH` | authorization-server | `http://localhost:8081`  | Origem (CSV) do **Swagger-UI** permitida no auth-server — o Swagger é cliente OAuth2 no browser e faz fetch cross-origin a `/oauth2/token`. Em prod, a origem pública do Swagger/borda. Compose usa `${VAR:-default}`. |

## Observabilidade

| Variável                                       | Serviço(s)                          | Compose                              | Observação                                  |
| ---------------------------------------------- | ----------------------------------- | ------------------------------------ | ------------------------------------------- |
| `MANAGEMENT_TRACING_EXPORT_ZIPKIN_ENDPOINT`    | user-service · gateway · auth-server | `http://zipkin:9411/api/v2/spans`    | Endpoint do Zipkin para exportar spans.     |
| `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SESSION`    | gateway                             | `DEBUG`                              | Toggle de debug (dev) da sessão.            |
| `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY_WEB` | gateway                           | `DEBUG`                              | Toggle de debug (dev) do Spring Security.   |

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
| `WEB_HOST_PORT`               | `interface`            | `5173`          | Porta do **host** da borda pública (mapeia para `:80` do container). Dev usa `5173`; prod tipicamente `80` (C16). |
