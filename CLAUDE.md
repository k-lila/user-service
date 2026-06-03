# CLAUDE.md

## Restrições de Execução

- Não refatore código fora do escopo explícito da tarefa pedida
- Não adicione tratamento de erro para cenários impossíveis
- Não crie arquivos sem ser pedido explicitamente
- Pergunte antes de agir se a tarefa tiver mais de 3 arquivos envolvidos
- Antes de cada alteração no código, apresente um relatório que aponte claramente 1) razões dos novos códigos; 2) arquivos a serem criados, se houver; 3) arquivos a serem modificados, se houver

---

## Visão Geral do Projeto

Sistema de microsserviços em Java + Spring para gerenciamento de usuários, pronto para produção. O objetivo central é fornecer uma base sólida de autenticação, registro e controle de acesso sobre a qual outras camadas de domínio serão adicionadas futuramente.

O front-end React (`login-interface`) implementa a autenticação no padrão **BFF**: o gateway é o cliente OAuth2, o SPA usa sessão por cookie e **não** manuseia JWT. Login, registro e logout (RP-initiated) funcionam ponta a ponta no Docker. Detalhes na seção _login-interface_.

---

## Arquitetura

```
login-interface (React)
        │
        ▼
    gateway :8081          ← único ponto de entrada externo
    ├── /users/register  → user-service
    ├── /oauth2/**       → authorization-server
    ├── /login           → authorization-server
    └── /users/**        → user-service
        │
        ├── authorization-server :8082
        │       └── chama user-service via Feign (endpoint interno)
        │
        ├── user-service :8090
        │       ├── MongoDB (persistência)
        │       └── Redis (cache + rate limiting)
        │
        ├── discovery-server :9091  (Eureka)
        ├── config-server :8888     (Spring Cloud Config)
        ├── zipkin :9411            (rastreamento distribuído)
        ├── prometheus :9090
        └── grafana :3000
```

**Tecnologias:**

- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.0, Maven
- MongoDB (dados de usuário), Redis (cache e rate limiting)
- React 19 + TypeScript + Vite + TailwindCSS 4 (front-end)
- Docker Compose para orquestração completa

---

## Estrutura de Arquivos

```
/
├── authorization-server/
│   └── src/main/java/authorizationserver/
│       ├── config/          # SecurityConfig, OAuth2ClientConfig, TokenCustomizerConfig, JWKConfig, CORSConfig
│       ├── services/        # AuthorizationService (UserDetailsService)
│       ├── clients/         # IUserClient (Feign → user-service)
│       └── dtos/            # AuthDTO
├── user-service/
│   └── src/main/java/com/users/userservice/
│       ├── config/          # SecurityConfig, CacheConfig, MongoConfig, OpenAPIConfig, WebConfig, CORSConfig
│       ├── controller/      # UserController, InternalUserController
│       ├── services/        # RegisterService, SearchService, AuthenticationService, CacheService
│       ├── domain/          # User.java (@Document MongoDB)
│       ├── repository/      # IUserRepository (MongoRepository)
│       ├── dtos/            # UserRequestDTO, UserResponseDTO, AuthDTO
│       └── exceptions/      # DomainEntityNotFound, EmailAlreadyRegisteredException, GlobalExceptionHandler
├── gateway/
│   └── src/main/java/com/users/gateway/
│       ├── config/          # SecurityConfig, RateLimiterConfig, OpenAPIConfig, CORSConfig
│       ├── routing/         # GatewayRouter
│       └── filter/          # CorrelationIdFilter, RateLimitLogFilter
├── discovery-server/
├── config-server/
│   └── src/main/resources/config/  # *.yml por serviço
├── login-interface/
│   └── src/
│       ├── api/             # authClient.ts, userClient.ts, apiAxios.ts
│       ├── hooks/           # useLogin, useRegister, useCurrentUser
│       ├── components/      # LoginBox, RegisterBox, NavBar, ProfileBox, ProtectedLayout
│       ├── pages/           # Login, Register, Dashboard
│       └── routes/          # router.tsx
├── grafana/
├── docker-compose.yml
└── prometheus.yml
```

---

## Serviços

### config-server (porta 8888)

- Gerencia configurações centralizadas via `classpath:/config`
- Todos os outros serviços importam configuração com `spring.config.import=optional:configserver:${CONFIG_SERVER_URL}`
- Arquivos de config: `config-server/src/main/resources/config/{nome-do-servico}.yml`
- **Deve ser o primeiro a subir.** Todos os demais dependem dele.

### discovery-server (porta 9091)

- Netflix Eureka — registra e descobre serviços
- Ele próprio **não** se registra no Eureka
- Deve subir logo após o config-server

### authorization-server (porta 8082)

- OAuth2 Authorization Server (Spring Security)
- Fluxo: authorization_code + PKCE + refresh_token
- Registra o cliente `gateway-client` em memória
- Busca credenciais do usuário chamando `user-service` via Feign: `GET /internal/users/email/{email}`
- Customiza o JWT com: `userID`, `roles`, `permissions` (`users.read`, `users.write`)
- Suporte a OIDC (escopos: `openid`, `profile`)
- Arquivo crítico: `TokenCustomizerConfig.java` — define o que vai no token

### user-service (porta 8090)

- Domínio central: CRUD de usuários
- Banco: MongoDB, coleção `users`
- Cache: Redis (TTL 5 min, caches: `"usersById"`, `"usersByEmail"` e `"authByEmail"`)
- Dois controllers:
  - `UserController` — endpoints públicos (via gateway)
  - `InternalUserController` — `GET /internal/users/email/{email}`, sem autenticação, **não exposto pelo gateway**, usado exclusivamente pelo authorization-server via Feign

**Endpoints expostos via gateway:**

| Método | Path                 | Auth       | Rate Limit      |
| ------ | -------------------- | ---------- | --------------- |
| POST   | /users/register      | Nenhuma    | 2 req/s (IP)    |
| GET    | /users               | ROLE_USER  | 10 req/s (user) |
| GET    | /users/{id}          | ROLE_USER  | 10 req/s (user) |
| GET    | /users/email/{email} | ROLE_USER  | 10 req/s (user) |
| GET    | /users/me            | ROLE_USER  | 10 req/s (user) |
| PUT    | /users               | ROLE_USER  | 10 req/s (user) |
| DELETE | /users/{id}          | ROLE_ADMIN | 10 req/s (user) |
| DELETE | /users/del/{id}      | ROLE_ADMIN | 10 req/s (user) |
| DELETE | /users/remove/me     | ROLE_USER  | 10 req/s (user) |

**Schema MongoDB:**

```js
{
  _id: ObjectId,
  name: String,       // 1–50 chars
  email: String,      // unique, formato e-mail
  passwordHash: String, // BCrypt
  registrationDate: ISODate,
  roles: [String],    // ex: ["USER"], ["USER", "ADMIN"]
  active: Boolean
}
```

**Estratégia de cache:**

- Três caches Redis distintos: `usersById` (chave = ID, valor `UserResponseDTO`), `usersByEmail` (chave = e-mail, valor `UserResponseDTO`) e `authByEmail` (chave = e-mail, valor `AuthDTO` — usado pelo login interno via `AuthenticationService.getUserByEmail`)
- Leitura (declarativa): `@Cacheable("usersById")` em `searchById`, `@Cacheable("usersByEmail")` em `searchByEmail` e `@Cacheable("authByEmail")` em `AuthenticationService.getUserByEmail`
- Escrita (manual via `CacheService`, que encapsula o `CacheManager`): `updateUser` atualiza `usersById` e `usersByEmail` (novo e-mail) e evicta o e-mail antigo em `usersByEmail` e `authByEmail`; `deleteUser` e `deactivateUser` evictam os três caches. A escrita é manual (não declarativa) porque cada cache usa uma chave diferente — ID vs e-mail

### gateway (porta 8081)

- Spring Cloud Gateway (WebFlux/reativo) — artefato `spring-cloud-starter-gateway-server-webflux` (Spring Cloud 2025.x)
- Único ponto de entrada externo — **nunca chame os serviços diretamente em produção**
- **Cliente OAuth2 do BFF:** `oauth2Login` + `oauth2Client` (cliente confidencial `gateway-client`) + resource server JWT. Guarda o token na sessão e o relaya downstream — o SPA nunca vê o JWT (ver seção _login-interface_)
- Rate limiting via Redis (token bucket):
  - LOW: 2 req/s, capacity 5 (registro, por IP)
  - MED: 5 req/s, capacity 10 (OAuth2, por IP)
  - HIGH: 10 req/s, capacity 20 (usuários autenticados, por user)
- Rotas definidas em **Java** (`GatewayRouter`, `RouteLocatorBuilder`). **`TokenRelay` é declarado por rota** (na rota `user-service`), **não** via `default-filters` do yaml — a DSL Java do `RouteLocatorBuilder` não recebe os default-filters
- **CSRF** habilitado (`CookieServerCsrfTokenRepository`, cookie `XSRF-TOKEN`; `/users/register` isento); entry point devolve **401** (não 302) para API não autenticada; **logout RP-initiated** (redireciona ao `end_session_endpoint` do auth-server)
- Filtros próprios: `CorrelationIdFilter`, `RateLimitLogFilter`
- Load balancing via Eureka (`lb://nome-do-servico`)

### login-interface (porta 5173 dev / 80 Docker)

- React 19 + TypeScript + Vite + TailwindCSS 4
- **Estado atual: BFF implementado e funcionando no Docker** — fluxo registro → login → perfil e **logout RP-initiated** validados ponta a ponta.

**Arquitetura: BFF (Backend-for-Frontend) — o gateway é o cliente OAuth2, não o SPA.**

O `gateway-client` é um cliente _confidencial_ (com secret); o gateway usa `oauth2Login` + `oauth2Client`. O SPA **não manuseia JWT**: apoia-se numa sessão estabelecida pelo gateway (cookie) e o gateway injeta o token nas chamadas downstream via `TokenRelay`.

**Por que BFF (Opção A) e não SPA-com-PKCE (Opção B):**

- **Segurança — o token nunca toca o browser.** Fica na sessão do gateway (servidor); o browser só carrega um cookie que pode ser `HttpOnly` + `Secure` + `SameSite`, inacessível ao JavaScript. Um XSS no front **não** consegue exfiltrar o JWT nem o refresh token. Isso **elimina de raiz** o gap conhecido "JWT em `localStorage`" (ver seção _Gaps de Segurança_), em vez de adiá-lo. PKCE protege apenas a _troca do código_; não protege o token depois de guardado no browser — que é exatamente o ponto fraco da Opção B.
- **É a recomendação atual** do IETF (OAuth 2.0 for Browser-Based Apps / BCP): para apps com backend, o padrão é o BFF, não o SPA segurando tokens.
- **O backend já está montado para isso.** A Opção B exigiria _registrar um segundo cliente público_ no auth-server (redirect URI do SPA, sem secret) e divergir do desenho atual. A Opção A reaproveita o que já existe.
- **Menos peça móvel no front = menos bug e menos manutenção:** sem lógica de PKCE, sem rota `/callback`, sem refresh manual, sem controle de expiração no cliente. O gateway centraliza tudo.
- **Alinha com a escala horizontal já planejada** (Spring Session Data Redis no gateway, ver _Trabalho Pendente §1_): concentrar a sessão no gateway segue esse plano; espalhar tokens pelo browser não ajuda na escala.
- **Trade-off (resolvido):** SPA e gateway em origens distintas → resolvido com **proxy same-origin** (Vite em dev, nginx no Docker), cookie first-party sem depender de `SameSite=None`. Em produção, front e API sob o mesmo domínio dispensam isso.

**Mecânica implementada (pontos-chave):**

- **Same-origin via proxy:** o SPA chama o gateway por caminho relativo na mesma origem (`:5173`) — proxy do **Vite** (dev) e **nginx** (Docker) para `/users`, `/oauth2`, `/login/oauth2`, `/logout` → gateway. (Só `/login/oauth2`, não `/login` puro, que é rota do SPA.)
- **Sem token no browser:** `apiAxios` com `withCredentials`, sem `Authorization: Bearer`; estado de auth derivado de `GET /users/me` (200 vs 401, `retry: false`). "Login" = redirect para `/oauth2/authorization/gateway-client`.
- **`TokenRelay` por rota:** declarado explicitamente na rota `user-service` do `GatewayRouter` (ver seção _gateway_).
- **Hostname OAuth em Docker (front vs back channel):** o `authorization-uri` é sobrescrito para `localhost:8082` (alcançável pelo browser), enquanto `issuer-uri`/token/jwks ficam no hostname **interno** `authorization-server:8082`. Assim o browser chega ao endpoint de autorização e o `iss` do token continua interno — validação no gateway e no user-service intacta.
- **CSRF:** habilitado no gateway; o axios envia `X-XSRF-TOKEN` (do cookie `XSRF-TOKEN`); `/users/register` é isento (público/pré-sessão).
- **Logout (RP-Initiated):** form oculto `POST /logout` (token CSRF no parâmetro `_csrf`, navegação top-level) → gateway encerra a sessão → redireciona ao `end_session_endpoint` (`localhost:8082/connect/logout`, com `id_token_hint` + `post_logout_redirect_uri`) → volta ao SPA deslogado; o próximo login **pede credenciais**.
- **CORS:** curativo (Opção A — user-service permite `http://localhost:5173`); endurecimento via C+D pendente (ver _Trabalho Pendente §3_).

**Fluxo BFF (implementado):**

```
1. Usuário clica "Login" → SPA navega para /oauth2/authorization/gateway-client (via proxy → gateway)
2. Gateway (oauth2Login) redireciona o browser a http://localhost:8082/oauth2/authorize (authorization-uri)
3. authorization-server exibe o form de login e autentica o usuário
4. authorization-server redireciona a http://localhost:5173/login/oauth2/code/gateway-client?code=... (via proxy → gateway)
5. Gateway troca o código por token (back-channel interno), guarda na sessão e seta o cookie SESSION
6. SPA chama GET /users/me com o cookie; TokenRelay injeta Authorization: Bearer; user-service valida e responde
7. Logado → SPA vai ao /dashboard (perfil). Logout → POST /logout → end_session do IdP → SPA deslogado
```

---

## Fluxo de Autenticação (OAuth2)

```
1. SPA → "Login" → /oauth2/authorization/gateway-client (gateway inicia o oauth2Login)
2. Gateway → redireciona o browser ao authorization-server (authorization-uri http://localhost:8082/oauth2/authorize)
3. authorization-server → exibe form de login
4. Usuário → submete credenciais
5. authorization-server → chama user-service /internal/users/email/{email} (Feign)
6. user-service → retorna dados do usuário (hash, roles)
7. authorization-server → valida senha (BCrypt), gera JWT com claims; redireciona ao gateway com o código
8. Gateway → troca o código por token (back-channel interno authorization-server:8082), guarda na sessão (cookie SESSION)
9. Requests subsequentes do SPA → cookie de sessão; o gateway relaya o JWT via TokenRelay downstream
10. Logout → POST /logout encerra a sessão e dispara o RP-Initiated Logout no IdP (end_session_endpoint)
```

**Claims customizados no JWT:**

- `userID`: ID do usuário no MongoDB
- `roles`: lista de roles (ex: `["USER"]`)
- `permissions`: `["users.read", "users.write"]`

---

## Convenções e Decisões de Design

- **Separação de responsabilidades rígida**: authorization-server não acessa MongoDB diretamente — apenas via Feign para user-service
- **Endpoint interno isolado**: `/internal/users/email/{email}` não está registrado nas rotas do gateway e não aparece no Swagger — é canal exclusivo entre authorization-server e user-service. Protegido por shared secret `X-Internal-Token` (`InternalTokenFilter` valida; `FeignConfig` do auth-server injeta) — acesso direto à porta 8090 sem o header recebe 403
- **Endpoints DELETE com semânticas distintas e intencionais**:
  - `DELETE /users/{id}` (ADMIN) → soft-delete (`deactivateUser`, seta `active = false`)
  - `DELETE /users/del/{id}` (ADMIN) → hard-delete (`deleteUser`, remove do banco)
  - `DELETE /users/remove/me` (USER, auto-remoção) → soft-delete (`deactivateUser`)
- **BCrypt** para hash de senha — custo padrão (10)
- **Roles são fixas**: apenas `USER` e `ADMIN`. Não há sistema de roles dinâmicas — roles são strings simples no MongoDB: `["USER"]`, `["USER", "ADMIN"]`
- **Configuração centralizada**: segredos vêm do config-server via variáveis de ambiente. Segredos hardcoded são gaps de segurança conhecidos (ver seção abaixo), não padrão intencional

---

## Desenvolvimento Local

### Subir tudo com Docker

```bash
docker compose up -d --build
```

### Ordem manual de inicialização (sem Docker)

1. `config-server` — `mvn spring-boot:run`
2. `discovery-server` — `mvn spring-boot:run`
3. `authorization-server` — `mvn spring-boot:run`
4. `user-service` — `mvn spring-boot:run`
5. `gateway` — `mvn spring-boot:run`
6. `login-interface` — `npm run dev`

### Variáveis de ambiente relevantes

- `CONFIG_SERVER_URL` — URL do config-server
- `EUREKA_URI` — URL do Eureka
- `AUTH_ISSUER_URI` — URI do authorization-server (para validação JWT)
- `MONGODB_URI` / `MONGODB_DATABASE`
- `REDIS_HOST` / `REDIS_PORT`
- `OAUTH_CLIENT_ID` / `OAUTH_CLIENT_SECRET` (gateway e authorization-server)
- `INTERNAL_API_TOKEN` (user-service e authorization-server) — shared secret do canal interno `/internal/**` (header `X-Internal-Token`). **Fail-fast:** sem default no config-server (o app não sobe sem ele); o `docker-compose.yml` injeta o default dev `internal-dev-token` para o `up` local. Deve ter o **mesmo** valor nos dois serviços; em produção, sobrescrever via env
- `OAUTH_REDIRECT_URI` (gateway) — `redirect-uri` enviado no fluxo OAuth2. Default prod-safe `{baseUrl}/login/oauth2/code/gateway-client`. No fluxo BFF, em **dev manual** (`npm run dev`) exporte `OAUTH_REDIRECT_URI=http://localhost:5173/login/oauth2/code/gateway-client` para o callback aterrissar no SPA (`:5173`). O `RegisteredClient` no authorization-server já permite essa URI
- `OAUTH_AUTHORIZATION_URI` (gateway) — endpoint de autorização **front-channel** (browser). Default `http://localhost:8082/oauth2/authorize`. Sobrescreve só esse endpoint; `issuer-uri`/token/jwks seguem o hostname interno
- `OAUTH_END_SESSION_URI` (gateway) — endpoint de logout OIDC do IdP (browser). Default `http://localhost:8082/connect/logout`
- `POST_LOGOUT_REDIRECT_URI` (gateway) — para onde o auth-server devolve o browser após o logout. Default `http://localhost:5173/` (deve bater com a `postLogoutRedirectUri` registrada no `RegisteredClient`)
- `VITE_API_URL` (front-end) — em dev/Docker fica **vazio** (chamadas relativas via proxy same-origin)

### URLs de acesso

| Serviço       | URL                                         |
| ------------- | ------------------------------------------- |
| Gateway / API | http://localhost:8081                       |
| Swagger UI    | http://localhost:8081/swagger-ui/index.html |
| Eureka        | http://localhost:9091                       |
| Zipkin        | http://localhost:9411                       |
| Prometheus    | http://localhost:9090                       |
| Grafana       | http://localhost:3000 (admin/admin)         |
| Front-end     | http://localhost:5173                       |

### Observabilidade

- **Zipkin**: rastreamento distribuído com B3 propagation, 100% sampling
- **Prometheus**: métricas expostas em `/actuator/prometheus` em todos os serviços; scrape a cada 5s
- **Grafana**: dashboards pré-provisionados, conectado ao Prometheus
- **SLOs configurados**: 50ms, 100ms, 200ms, 500ms, 1s, 2s

---

## Estratégia de Testes

**Estado atual:** 32 testes unitários + 44 testes de controller + 32 testes de integração (Testcontainers), BUILD SUCCESS em ambos os módulos.

**Unitários (Mockito):**

| Serviço                                       | Arquivo de teste                                                  | Testes |
| --------------------------------------------- | ----------------------------------------------------------------- | ------ |
| `RegisterService`                             | `user-service/.../services/RegisterServiceTest.java`              | 16     |
| `SearchService`                               | `user-service/.../services/SearchServiceTest.java`                | 7      |
| `AuthenticationService` (user-service)        | `user-service/.../services/AuthenticationServiceTest.java`        | 3      |
| `AuthorizationService` (authorization-server) | `authorization-server/.../services/AuthorizationServiceTest.java` | 6      |

**Controller (`@WebMvcTest` — MockMvc + `SecurityMockMvcRequestPostProcessors.jwt()`):**

| Foco                                                                         | Arquivo de teste                                              | Testes |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------- | ------ |
| Status HTTP, autorização (`ROLE_USER`/`ROLE_ADMIN`/sem token), extração JWT, validação `@Valid` (400) | `user-service/.../controller/UserControllerTest.java`         | 40     |
| Endpoint interno `/internal/users/email/{email}` (200/404 com `X-Internal-Token`, 403 sem/errado) | `user-service/.../controller/InternalUserControllerTest.java` | 4      |

> Usa `@Import({SecurityConfig.class, GlobalExceptionHandler.class})` — em Spring Boot 4.0 o slice `@WebMvcTest` não carrega essas classes automaticamente.

**Integração (Testcontainers — MongoDB `mongo:7` + Redis `redis:7-alpine`):**

| Foco                                                                             | Arquivo de teste                                            | Testes |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------- | ------ |
| Fluxo registro → busca → atualização → desativação/remoção, unicidade de e-mail e índice único no banco | `user-service/.../integration/UserFlowIntegrationTest.java` | 18     |
| Comportamento do cache Redis (popular/evictar `usersById` e `usersByEmail`)      | `user-service/.../integration/CacheIntegrationTest.java`    | 14     |

> Base comum: `AbstractIntegrationTest` sobe os containers, mocka o `JwtDecoder` e limpa Redis (`flushDb`) + os caches `usersById`/`usersByEmail` entre os testes.

> **Visibilidade eventual do `RedisCache`:** neste stack (Spring Boot 4.0.1 / spring-data-redis 4.0.1 / Lettuce 6.8.1, sem `commons-pool2`), `cache.put(...)` fica visível para um `cache.get(...)` da mesma chave com atraso de ~1–3 ms. Por isso testes de cache **não fazem read-after-write direto**: as leituras declarativas (`@Cacheable`) chamam `search*` duas vezes (a 1ª dispara o put, a 2ª lê já visível) e as asserções sobre puts manuais via `CacheService` usam `await().atMost(...).untilAsserted(...)` (Awaitility, escopo `test`). O código de produção está correto — em produção a leitura vem em requisição HTTP posterior, então o atraso é irrelevante.

**Front-end (`login-interface`):** **sem cobertura de testes hoje** — não há `vitest`/RTL nem script `test` no `package.json`. O único gate é o `npm run build` (`tsc -b` + `vite build`, typecheck). Bateria de testes planejada (ver _Trabalho Pendente §4_) para o front BFF, que já está implementado (ver seção _login-interface_).

---

## Estratégia de Logs

Logging via SLF4J (`LoggerFactory.getLogger(Classe.class)`, `private static final LOGGER` por classe), sempre **parametrizado** (`{}`, nunca concatenação).

**Níveis (convenção):**

- `INFO` — eventos de fluxo de negócio bem-sucedidos (entrada de endpoint, registro, atualização, busca encontrada, `auth` enviando credenciais) e a requisição recebida no gateway
- `WARN` — anomalias esperadas e recuperáveis: e-mail já cadastrado, entidade não encontrada (404), argumento inválido (400), senha ausente, falha de login e rejeição por rate limit (429)
- `ERROR` — falhas inesperadas com stacktrace: handler genérico 500 e falha de comunicação Feign (authorization-server → user-service)
- `DEBUG` — alto volume / baixo valor operacional: operações de cache (`put`/`evict`) no `CacheService`

**Formato e convenções de escrita:** padrão em pipe, fácil de filtrar via grep. Estrutura `"| [VERBO_HTTP] | ação | campo: valor"`:

- Segmentos separados por `|`; toda mensagem **começa** com `| `
- Verbo HTTP, quando presente, em **maiúsculas** (`POST`, `GET`, `PUT`, `DELETE`); ações de domínio em **minúsculas e infinitivo/pt-br** (`registrar`, `buscar`, `atualizar`, `desativação`, `deleção`)
- Chave de campo padronizada: `ID:` (sempre maiúsculo), `email:`, `nome:`, `motivo:`, `correlationId:`; múltiplos campos no mesmo segmento separados por `, ` (ex.: `nome: {}, ID: {}`)
- Pares simétricos sucesso/falha compartilham o mesmo prefixo de ação (ex.: `| busca por ID | encontrado` ↔ `| busca por ID | não encontrado`)
- Logs do fluxo de autenticação (em ambos os módulos) usam o namespace `| auth | ...` (`carregando usuário`, `enviando credenciais`, `login falhou`, `falha Feign user-service`, `inexistente ou inativo`)
- Sem texto em CAIXA ALTA em inglês e sem concatenação — sempre `{}` parametrizado

**Correlação entre serviços:** o `logging.pattern.level` (definido nos `*.yml` do config-server para user-service, gateway e authorization-server) inclui `traceId`/`spanId` do Micrometer — propagados via B3/Zipkin de ponta a ponta. O gateway também loga o `X-Correlation-ID` na borda. MDC não é usado no gateway (reativo) porque não propaga de forma confiável no WebFlux.

**PII / LGPD:** e-mails nunca são logados em claro. `LogUtils.maskEmail()` (um por módulo: `user-service/.../util/` e `authorization-server/.../util/`) mascara para `f***@dominio`. IDs de usuário (não-PII) são logados normalmente.

**Cobertura por classe (pontos logados):**

| Classe                   | Camada                           | Destaque                                                                             |
| ------------------------ | -------------------------------- | ------------------------------------------------------------------------------------ |
| `UserController`         | user-service / controller        | entrada dos 9 endpoints                                                              |
| `InternalUserController` | user-service / controller        | entrada do auth interno (e-mail mascarado)                                           |
| `RegisterService`        | user-service / service           | registro/update/desativar/deletar + rejeições (WARN)                                 |
| `SearchService`          | user-service / service           | página/encontrado (INFO) + não encontrado (WARN)                                     |
| `AuthenticationService`  | user-service / service           | `auth` — enviando credenciais (INFO) / inexistente ou inativo (WARN)                 |
| `CacheService`           | user-service / service           | put/evict dos 3 caches (DEBUG)                                                       |
| `GlobalExceptionHandler` | user-service / exceptions        | 404/409/400 (WARN), 500 (ERROR), 403 relançado p/ Spring Security                    |
| `AuthorizationService`   | authorization-server / service   | `auth` — carregando usuário (INFO) + falha Feign user-service com stacktrace (ERROR) |
| `AuthFailureListener`    | authorization-server / listeners | falhas de login via `AbstractAuthenticationFailureEvent` (WARN)                      |
| `CorrelationIdFilter`    | gateway / filter                 | requisição recebida + `correlationId`                                                |
| `RateLimitLogFilter`     | gateway / filter                 | rejeições 429 (WARN)                                                                 |

> Nota: o handler `@ExceptionHandler(AccessDeniedException.class)` no `GlobalExceptionHandler` **relança** a exceção — sem ele, o catch-all `Exception` transformaria os 403 do `@PreAuthorize` em 500.

---

## Trabalho Pendente

> Reorganizado por tema/prioridade. Decisões de contexto: o sistema rodará em **múltiplas instâncias** (escala horizontal) e serve de **base/template reutilizável**. Ordem sugerida de execução: 1 → 2 → 3 → 4 → 5 (evolução de domínio vem depois).
>
> Cada item recebe **Severidade** (Alta/Média/Baixa — impacto no objetivo de base pronta para produção e multi-instância) e **Esforço** (P/M/G).

### 1. Escalabilidade horizontal (pré-requisito — hoje quebra com N instâncias)

- [ ] **Chaves JWK persistentes** no authorization-server — `JWKConfig.java` gera um par RSA novo a cada boot, em memória. Os resource servers validam via `issuer-uri`/JWKS; um JWT assinado pela instância A não valida na JWKS da instância B, e todo restart/deploy invalida os tokens vigentes. Carregar o par de chaves de keystore/secret externo (PEM ou JKS), com `kid` fixo compartilhado entre instâncias. · **Sev: Alta · Esforço: M**
- [ ] **Persistir estado OAuth em DB relacional** — hoje `InMemoryRegisteredClientRepository` + os defaults `InMemoryOAuth2AuthorizationService`/`InMemoryOAuth2AuthorizationConsentService`. O `code` emitido por uma instância não pode ser trocado por token em outra. Migrar para `JdbcRegisteredClientRepository`, `JdbcOAuth2AuthorizationService` e `JdbcOAuth2AuthorizationConsentService` (schemas oficiais do Spring Authorization Server). Requer datasource novo (ex.: Postgres): `authorization-server/pom.xml` (`spring-boot-starter-jdbc` + driver), `docker-compose.yml` (serviço + volume), `config-server/.../authorization-server.yml` (`spring.datasource.*`) · **Sev: Alta · Esforço: G**
- [ ] **Sessão HTTP compartilhada** — o login/consent do authorization-server e o `oauth2Login` + `ServerOAuth2AuthorizedClientRepository` do gateway dependem de sessão em memória. Adicionar Spring Session Data Redis (servlet no auth-server, reactive no gateway — que já tem Redis) ou sticky sessions no load balancer · **Sev: Alta · Esforço: M**
- [ ] **Deploy rolling sem downtime** — `server.shutdown=graceful` + readiness/liveness probes (actuator) em todos os serviços. (Nota: o `docker-compose.yml` já tem healthchecks de _container_ e `depends_on: condition: service_healthy`; falta o nível de aplicação.) · **Sev: Média · Esforço: M**
- [ ] **(Infra) Eliminar SPOFs** — `discovery-server` em peer replication (Eureka HA), `config-server` replicado, MongoDB replica set e Redis Sentinel/Cluster · **Sev: Média · Esforço: G**

> Já adequados para escala: validação JWT stateless no resource server, rate limiter no Redis, os caches (`usersById`/`usersByEmail`/`authByEmail`) no Redis e os healthchecks de container + `depends_on` no `docker-compose.yml`.

### 2. Resiliência e consistência

- [ ] Adicionar Resilience4j como circuit breaker na chamada Feign do authorization-server → user-service · **Sev: Alta · Esforço: M**
  - `authorization-server/pom.xml` — adicionar `spring-cloud-starter-circuitbreaker-resilience4j`
  - Criar `clients/UserClientFallbackFactory.java` — `FallbackFactory<IUserClient>` que lança `UsernameNotFoundException` ao acionar o fallback
  - `clients/IUserClient.java` — adicionar `fallbackFactory = UserClientFallbackFactory.class` no `@FeignClient`
  - `config-server/.../authorization-server.yml` — habilitar `spring.cloud.openfeign.circuitbreaker.enabled=true` e adicionar bloco `resilience4j.circuitbreaker.instances.user-service` com `slidingWindowSize`, `failureRateThreshold`, `waitDurationInOpenState` e `permittedNumberOfCallsInHalfOpenState`
- [ ] **`permissions` derivadas das roles no `TokenCustomizerConfig`** — hoje `["users.read","users.write"]` é hardcoded para todo usuário; mapear roles → permissions (ex.: ADMIN ganha `users.delete`) · **Sev: Média · Esforço: P**

### 3. Segurança / hardening

- [ ] **CORS: fronteira única no gateway + origens configuráveis (C + D)** — hoje há CORS em 3 módulos (`CORSConfig.java` no gateway, user-service e auth-server) com origens hardcoded. O gateway repassa o header `Origin` do browser ao user-service, cujo `CorsFilter` (allowlist `8081/8082`) rejeitava `localhost:5173` → **403 "Invalid CORS request"** + CORS dobrado (`vary` duplicados). **Estado atual (curativo):** Opção A aplicada — `localhost:5173` adicionado à allowlist do `user-service/.../config/CORSConfig.java`. **Alvo:** CORS só no gateway (borda BFF) e configurável por ambiente. · **Sev: Média · Esforço: M**
  - **Princípio:** CORS é mecanismo de browser e só importa na borda que o browser toca (o gateway). Em serviço interno não agrega segurança (o guard real é JWT + isolamento de rede) — só atrito. Pré-condição de C: confirmar que o user-service **nunca** é chamado direto pelo browser (só via gateway); se houver acesso direto, usar a **Opção B** (gateway remove o header `Origin` ao rotear `/users/**` via `RemoveRequestHeader=Origin`) em vez de C.
  - **C — remover CORS do user-service:** apagar o bean `CorsFilter` (`user-service/.../config/CORSConfig.java`) **e** o `.cors(Customizer.withDefaults())` do `user-service/.../config/SecurityConfig.java` (deixar só um vira no-op sujo). Reverter o curativo da Opção A (a linha `localhost:5173` deixa de existir). Verificar que nenhum teste depende do bean `CorsFilter` (a suíte foca status/auth/cache — improvável).
  - **D — externalizar origens do gateway:** `gateway/.../config/CORSConfig.java` lê de property (`@Value`/`@ConfigurationProperties`) em vez de hardcode; `config-server/.../gateway.yml` adiciona `cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}` (default dev sensato; env CSV → `List<String>`); `docker-compose.yml` seta `CORS_ALLOWED_ORIGINS` quando diferir.
  - **Footguns de D:** (1) `allowCredentials(true)` **proíbe** `allowedOrigins("*")` — o Spring falha no startup; para curinga de domínio usar `setAllowedOriginPatterns(...)`, não `setAllowedOrigins`. (2) Erro silencioso de origem (scheme errado, `/` no fim) quebra o SPA com CORS difícil de diagnosticar → logar a allowlist efetiva no startup e documentar que prod **deve** setar a origem real.
  - **C' (próxima rodada, fora do escopo imediato):** o auth-server também tem `CORSConfig`, mas no fluxo atual o browser **navega** para `localhost:8082` (login) — navegação top-level não é CORS (só XHR é), então o CORS dele provavelmente também é removível. Avaliar para não deixar hardening pela metade.
  - **Escopo/validação:** 3 módulos, >3 arquivos → apresentar plano e confirmar antes. CORS só se valida **ao vivo** (rebuild gateway + user-service + browser): re-testar registro, `GET /users/me` e uma chamada mutável. **Sequenciamento:** fazer **depois** do baseline docker (com a Opção A) comprovadamente estável — C substitui A.
- [ ] **Secrets fora do `docker-compose.yml`** — mover credenciais do Mongo (`user_service:user_1234321`) e do Grafana (`admin/admin`) para `.env` git-ignored · **Sev: Média · Esforço: P**
- [ ] TLS/HTTPS — manter como gap conhecido (decidido: configurar com a infra de produção) · **Sev: Alta · Esforço: M**

### 4. Qualidade, testes e padronização de API

- [ ] **Padronizar respostas de erro em RFC 7807 / `ProblemDetail`** — hoje o `GlobalExceptionHandler` retorna `String` crua e o `@Valid` sai no formato default do Spring (inconsistente). Unificar 400/404/409/500 + validação num único formato. (Engloba o handler de `@Valid` do tema 2.) · **Sev: Média · Esforço: M**
- [ ] **Cobrir gateway e authorization-server com testes** — o gateway tem só `contextLoads`; o authorization-server só `AuthorizationServiceTest`. Adicionar: roteamento + rate-limit (gateway), `TokenCustomizerConfig` / `JWKConfig` / `SecurityConfig` (auth-server). · **Sev: Média · Esforço: M**
- [ ] **Criar bateria de testes do front-end (`login-interface`)** — hoje **zero cobertura** (só o typecheck do `npm run build`). Montar a stack de testes e cobrir a aplicação React (BFF já implementado, ver seção _login-interface_). · **Sev: Média · Esforço: M**
  - **Stack:** `vitest` + `@testing-library/react` + `@testing-library/user-event` + `jsdom`; `msw` (Mock Service Worker) para simular o gateway; adicionar script `"test"` no `package.json` e config de testes no `vite.config.ts` (ou `vitest.config.ts`)
  - **Unitários/componente:** `authClient` (`register` → `POST /users/register` com `password`; helpers `login`/`logout` disparam redirect para `/oauth2/authorization/gateway-client` e `/logout`), hooks (`useCurrentUser` trata `401` como deslogado; `useRegister` navega no sucesso), componentes (`LoginBox` renderiza botão de redirect — sem form de senha; `RegisterBox` mapeia o campo `password`; `ProtectedLayout` redireciona em `401`; `NavBar` faz logout via redirect)
  - **Integração (MSW):** fluxo de registro e derivação do estado autenticado por `GET /users/me` (200 vs 401), sem depender de `localStorage`
  - **E2E (opcional, posterior):** Playwright cobrindo o fluxo de redirect OAuth2 ponta a ponta — exige o stack de serviços de pé (candidato ao item de CI/CD, §5)
  - **CI:** incluir `npm ci && npm run test` (e `npm run build`) do `login-interface` no pipeline (ver _Trabalho Pendente §5_, hoje focado só nos 5 módulos Java)
- [ ] **Tornar o `contextLoads` do gateway hermético** — hoje o `@SpringBootTest` (`GatewayApplicationTests`) faz **OIDC discovery real** na subida (via `issuer-uri` vindo do config-server) e depende de um authorization-server alcançável e com issuer compatível — falha fora desse cenário (ex.: stack Docker no ar reporta issuer `http://authorization-server:8082`, mas o teste pede `http://localhost:8082`). Isolar com `gateway/src/test/resources/application.yml` (sem import do config-server; endpoints do provider explícitos ou autoconfig OAuth2 client/resource-server desabilitada no teste). Faz par com o item "Cobrir gateway e authorization-server com testes" acima. · **Sev: Baixa · Esforço: P**

### 5. Eficiência e CI/CD-operação

- [ ] **Eliminar a dupla chamada Feign por login** — `AuthorizationService.loadUserByUsername` e `TokenCustomizerConfig.jwtCustomizer` chamam `getUserByEmail` separadamente a cada login. Reaproveitar o resultado (principal/atributo) ou unificar. Mitigado hoje pelo cache `authByEmail`. · **Sev: Baixa · Esforço: M**
- [ ] **Pipeline de CI** (ex.: GitHub Actions) — build + testes dos 5 módulos Java + do front-end (`login-interface`: `npm ci`, `npm run build` e `npm run test` quando a bateria do §4 existir) a cada push/PR; os testes de integração com Testcontainers exigem Docker no runner. · **Sev: Média · Esforço: M**
- [ ] **Versionamento de API** (`/v1/...`) antes de adicionar novas camadas de domínio sobre a base. · **Sev: Baixa · Esforço: M**

### 6. Evolução de domínio (não imediatos)

- [ ] Verificação de e-mail no cadastro · **Sev: Baixa · Esforço: M**
- [ ] Recuperação de senha · **Sev: Baixa · Esforço: M**
- [ ] **Auditoria de eventos** — registrar logins, alterações de roles e deleções (trilha de auditoria) · **Sev: Baixa · Esforço: M**
- [ ] **Gestão de admin** — decisão atual: criar/promover ADMIN manualmente no MongoDB (ex.: `db.users.updateOne({email: ...}, {$addToSet: {roles: "ADMIN"}})`). Sem automação por ora; sem isso as rotas `ROLE_ADMIN` ficam inalcançáveis · **Sev: Baixa · Esforço: M**
- [ ] Outros microsserviços de negócio serão adicionados sobre esta base após o sistema de usuários estar estável · **Sev: — · Esforço: G**

---

## Correções Necessárias

> Levantamento de **tecnologias já implementadas que precisam de correção ou de fechamento de implementação** — foco em solidez do que existe, não em novas tecnologias. Cada item tem **Dificuldade (1–10)** = esforço/risco da correção, e **Impacto (1–10)** = quanto a falha compromete a solidez/"produção" da base.
>
> _Tier 1 (C1–C5) concluído e removido. Itens restantes abaixo._

### Tier 2 — tecnologias implementadas mas não sólidas

- [ ] **C6 — OAuth2/JWT quebram com N instâncias.** `JWKConfig.java:24-34` gera RSA novo por boot (em memória, `kid` aleatório); `OAuth2ClientConfig.java:33` usa `InMemoryRegisteredClientRepository`; sessões de login/consent e `oauth2Login` em memória. Detalhamento e plano completos no _Trabalho Pendente §1_ (chaves JWK persistentes, estado OAuth em JDBC, Spring Session Redis). · **Dificuldade: 8/10 · Impacto: 9/10**
- [ ] **C7 — Sem resiliência na chamada Feign.** `IUserClient.java:9` sem `fallbackFactory`; indisponibilidade do user-service derruba o login. Adicionar Resilience4j (ver _Trabalho Pendente §2_). · **Dificuldade: 5/10 · Impacto: 6/10**
- [ ] **C8 — `permissions` hardcoded no JWT.** `TokenCustomizerConfig.java:34-37` injeta `["users.read","users.write"]` para todo usuário (inclusive ADMIN). Derivar das roles. · **Dificuldade: 3/10 · Impacto: 5/10**
- [ ] **C9 — Erros não padronizados (RFC 7807).** `GlobalExceptionHandler` devolve `String` crua (inclusive o handler de `@Valid`, que já retorna 400 mas em texto). Unificar 400/404/409/500 + validação em `ProblemDetail`. · **Dificuldade: 4/10 · Impacto: 5/10**

### Tier 3 — rede de proteção e acabamento

- [ ] **C10 — Cobertura desigual de testes.** Gateway só `contextLoads` (e não-hermético — faz OIDC discovery real), auth-server só `AuthorizationServiceTest`, front-end zero. Cobrir roteamento/rate-limit, `TokenCustomizerConfig`/`JWKConfig`, e o BFF; isolar o `contextLoads` do gateway. · **Dificuldade: 5/10 · Impacto: 6/10**
- [ ] **C11 — Secrets em claro no `docker-compose.yml`.** Mongo (`user_service:user_1234321`, linhas 184-185), `OAUTH_CLIENT_SECRET` (linha 145) e Grafana `admin/admin` (215-216). Mover para `.env` git-ignored. · **Dificuldade: 2/10 · Impacto: 5/10**
- [ ] **C12 — CORS duplicado e hardcoded.** `CorsFilter` em 3 módulos com origens fixas (user-service `CORSConfig.java:19-23`); já causou 403/`vary` duplicado. Consolidar na borda (gateway) e externalizar por ambiente (ver _Trabalho Pendente §3_, opções C+D). Validar ao vivo. · **Dificuldade: 5/10 · Impacto: 4/10**
- [ ] **C13 — Validação de senha dividida e fraca.** `UserRequestDTO.java:21` (`@Size(min=8)` nullable) + checagem manual de null em `RegisterService:38`; sem regra de complexidade. Unificar. · **Dificuldade: 3/10 · Impacto: 4/10**
- [ ] **C14 — Dupla chamada Feign por login.** `loadUserByUsername` + `jwtCustomizer` chamam `getUserByEmail` separadamente (mitigado por cache `authByEmail`). Reaproveitar o resultado (ver _Trabalho Pendente §5_). · **Dificuldade: 4/10 · Impacto: 3/10**
- [ ] **C15 — Higiene cosmética.** Campos `private` não-`final` + `@Autowired` redundante no construtor (`UserController.java:38-45`, `SearchService`); `RegisterService.updateUser:88,100` monta o `UserResponseDTO` duas vezes. · **Dificuldade: 1/10 · Impacto: 2/10**

---

## Gaps de Segurança Conhecidos

Identificados e com decisão de abordagem registrada:

| Gap                                                          | Localização               | Severidade | Decisão                                                                                                                                                                   |
| ------------------------------------------------------------ | ------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| JWT armazenado em `localStorage` no front-end                | `login-interface/` (BFF)  | Resolvido  | **Resolvido:** o BFF mantém o JWT na sessão do gateway; o browser só recebe o cookie de sessão. O `localStorage` foi **eliminado** do front (ver seção _login-interface_) |
| Grafana acessível com `admin/admin`                          | `docker-compose.yml`      | Baixa      | Alterar credenciais e proteger o stack de observabilidade                                                                                                                 |
| Sem HTTPS/TLS                                                | Todo o sistema            | Alta       | Decidido: configurar junto com a infraestrutura de produção                                                                                                               |
| `InMemoryRegisteredClientRepository` no authorization-server | `OAuth2ClientConfig.java` | Baixa      | Aceitável enquanto houver apenas um cliente (gateway). Migrar para JDBC se necessário                                                                                     |
