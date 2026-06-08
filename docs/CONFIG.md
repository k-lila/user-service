# Variáveis de Ambiente

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler esta referência](#como-ler-esta-referência)
- [Infraestrutura e descoberta](#infraestrutura-e-descoberta)
- [Dados (Mongo · Postgres · Redis)](#dados-mongo--postgres--redis)
- [OAuth2 e JWT](#oauth2-e-jwt)
- [Canal interno](#canal-interno)
- [Swagger / OpenAPI](#swagger--openapi)
- [Observabilidade](#observabilidade)
- [Front-end](#front-end)
- [Containers de infraestrutura (só `docker-compose`)](#containers-de-infraestrutura-só-docker-compose)

## Como ler esta referência

- **Default dev** é o valor embutido (no `*.yml` do config-server ou em `@Value` no código) quando a variável não é exportada.
- Variáveis **sem default** são _fail-fast_: o app não sobe sem elas (o `docker-compose.yml` injeta um valor de dev).
- A coluna **Serviço(s)** indica quem consome a variável.

## Infraestrutura e descoberta

| Variável            | Serviço(s)             | Default dev                     | Observação                                                            |
| ------------------- | ---------------------- | ------------------------------- | -------------------------------------------------------------------- |
| `SERVER_PORT`       | todos                  | por serviço¹                    | Porta HTTP do serviço.                                                |
| `CONFIG_SERVER_URL` | todos (exceto config)  | —                               | `spring.config.import=optional:configserver:...`. Compose: `http://config-server:8888`. |
| `EUREKA_URI`        | todos                  | `http://localhost:9091/eureka`  | `defaultZone` do Eureka.                                              |

> ¹ Defaults de porta: gateway `8081`, authorization-server `8082`, user-service `8090`, discovery-server `9091`, config-server `8888`.

## Dados (Mongo · Postgres · Redis)

| Variável            | Serviço(s)                          | Default dev                                  | Observação                                                       |
| ------------------- | ----------------------------------- | -------------------------------------------- | ---------------------------------------------------------------- |
| `MONGODB_URI`       | user-service                        | — (obrigatória)                              | Compose: `mongodb://user_service:...@user-mongo:27017/...`.      |
| `MONGODB_DATABASE`  | user-service                        | `user-db`                                    | Base de dados de usuários.                                        |
| `REDIS_HOST`        | user-service · gateway · auth-server | `localhost`                                  | Cache + rate limit (user), rate limit + **sessão** (gw/auth).    |
| `REDIS_PORT`        | user-service · gateway · auth-server | `6379`                                       |                                                                  |
| `AUTH_DB_URL`       | authorization-server                | `jdbc:postgresql://localhost:5432/authdb`    | Datasource do Postgres que guarda o estado OAuth.                |
| `AUTH_DB_USER`      | authorization-server                | `auth_service`                               |                                                                  |
| `AUTH_DB_PASSWORD`  | authorization-server                | `auth_1234321`                               | Default dev; sobrescrever em produção.                           |

## OAuth2 e JWT

| Variável                   | Serviço(s)            | Default dev                                          | Observação                                                                                  |
| -------------------------- | --------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `AUTH_ISSUER_URI`          | user-service · gateway | `http://localhost:8082`                              | `issuer-uri` para validação do JWT (resource server). Hostname **interno** (back-channel).  |
| `AUTH_ISSUER`              | authorization-server  | `http://localhost:8082`                              | Issuer que o próprio auth-server anuncia.                                                    |
| `JWK_PRIVATE_KEY`          | authorization-server  | `classpath:keys/app.key`                             | Chave privada RSA (PEM). **Default dev** — em prod usar secret montado (`file:/run/secrets/...`). |
| `JWK_PUBLIC_KEY`           | authorization-server  | `classpath:keys/app.pub`                             | Chave pública RSA (PEM). **Default dev**.                                                    |
| `JWK_KEY_ID`               | authorization-server  | `user-service-key`                                   | `kid` estável da chave de assinatura.                                                        |
| `OAUTH_CLIENT_ID`          | gateway · auth-server | `gateway-client`                                     | Client confidencial do BFF.                                                                  |
| `OAUTH_CLIENT_SECRET`      | gateway · auth-server | `gateway-secret`                                     | Default dev; sobrescrever em produção.                                                       |
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

| Variável                      | Container       | Compose         |
| ----------------------------- | --------------- | --------------- |
| `POSTGRES_DB`                 | `auth-postgres` | `authdb`        |
| `POSTGRES_USER`               | `auth-postgres` | `auth_service`  |
| `POSTGRES_PASSWORD`           | `auth-postgres` | `auth_1234321`  |
| `MONGO_INITDB_ROOT_USERNAME`  | `user-mongo`    | `user_service`  |
| `MONGO_INITDB_ROOT_PASSWORD`  | `user-mongo`    | `user_1234321`  |
