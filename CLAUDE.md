# CLAUDE.md

## Restrições de Execução

- Não refatore código fora do escopo explícito da tarefa pedida
- Não adicione tratamento de erro para cenários impossíveis
- Não crie arquivos sem ser pedido explicitamente
- Pergunte antes de agir se a tarefa tiver mais de 3 arquivos envolvidos
- Ao aprensentar os códigos a serem implementados, sempre aponte claramente as razões dos novos códigos; e 1) arquivos a serem modificados, se houver e 2) arquivos a serem criados, se houver

---

## Visão Geral do Projeto

Sistema de microsserviços em Java + Spring para gerenciamento de usuários, pronto para produção. O objetivo central é fornecer uma base sólida de autenticação, registro e controle de acesso sobre a qual outras camadas de domínio serão adicionadas futuramente.

O front-end React existe **apenas como demonstração** do fluxo OAuth2/JWT. Ele está incompleto e incompatível com o fluxo OAuth2 atual — foi construído para uma autenticação simples (token direto via POST /login) e ainda não foi adaptado para o fluxo de autorização via código com PKCE.

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
│       └── filter/          # CorrelationIdFilter, JwtHeaderPropagationFilter
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

- Spring Cloud Gateway (WebFlux/reativo)
- Único ponto de entrada externo — **nunca chame os serviços diretamente em produção**
- Rate limiting via Redis (token bucket):
  - LOW: 2 req/s, capacity 5 (registro, por IP)
  - MED: 5 req/s, capacity 10 (OAuth2, por IP)
  - HIGH: 10 req/s, capacity 20 (usuários autenticados, por user)
- Filtros: `CorrelationIdFilter`, `JwtHeaderPropagationFilter`, `TokenRelay`
- Load balancing via Eureka (`lb://nome-do-servico`)

### login-interface (porta 5173 dev / 80 Docker)

- React 19 + TypeScript + Vite + TailwindCSS 4
- **Estado atual: incompleto e incompatível com o fluxo OAuth2**
  - Foi construído para autenticação direta (POST /login com credenciais, recebendo token)
  - O authorization-server usa authorization_code com PKCE — o front-end precisa ser reescrito
- Token armazenado em `localStorage` (interceptor Axios adiciona `Authorization: Bearer`)
- Estrutura: `pages/`, `components/`, `hooks/`, `api/`

**Plano de reescrita — SPA redirect-based (authorization_code + PKCE):**

```
1. Usuário clica "Login" no front-end
2. Front-end redireciona para: GET /oauth2/authorize?response_type=code&client_id=...&code_challenge=...
3. authorization-server exibe form de login e autentica o usuário
4. authorization-server redireciona de volta ao front-end com ?code=...
5. Front-end faz POST /oauth2/token trocando o código pelo JWT
6. Token armazenado e usado nas requisições subsequentes
```

---

## Fluxo de Autenticação (OAuth2)

```
1. Usuário → GET /oauth2/authorize (gateway)
2. Gateway → redireciona para authorization-server
3. authorization-server → exibe form de login
4. Usuário → submete credenciais
5. authorization-server → chama user-service /internal/users/email/{email}
6. user-service → retorna dados do usuário (hash, roles)
7. authorization-server → valida senha (BCrypt), gera JWT com claims customizados
8. JWT → retornado ao gateway via redirect com código de autorização
9. Gateway → troca código por token (/oauth2/token)
10. Requests subsequentes → JWT propagado via TokenRelay para os serviços downstream
```

**Claims customizados no JWT:**

- `userID`: ID do usuário no MongoDB
- `roles`: lista de roles (ex: `["USER"]`)
- `permissions`: `["users.read", "users.write"]`

---

## Convenções e Decisões de Design

- **Separação de responsabilidades rígida**: authorization-server não acessa MongoDB diretamente — apenas via Feign para user-service
- **Endpoint interno isolado**: `/internal/users/email/{email}` não está registrado nas rotas do gateway e não aparece no Swagger — é canal exclusivo entre authorization-server e user-service
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
- `VITE_API_URL` (front-end)

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

**Estado atual:** 32 testes unitários + 40 testes de controller + 31 testes de integração (Testcontainers), BUILD SUCCESS em ambos os módulos.

**Unitários (Mockito):**

| Serviço                                       | Arquivo de teste                                                  | Testes |
| --------------------------------------------- | ----------------------------------------------------------------- | ------ |
| `RegisterService`                             | `user-service/.../services/RegisterServiceTest.java`              | 16     |
| `SearchService`                               | `user-service/.../services/SearchServiceTest.java`                | 7      |
| `AuthenticationService` (user-service)        | `user-service/.../services/AuthenticationServiceTest.java`        | 3      |
| `AuthorizationService` (authorization-server) | `authorization-server/.../services/AuthorizationServiceTest.java` | 6      |

**Controller (`@WebMvcTest` — MockMvc + `SecurityMockMvcRequestPostProcessors.jwt()`):**

| Foco                                                                        | Arquivo de teste                                                 | Testes |
| --------------------------------------------------------------------------- | ---------------------------------------------------------------- | ------ |
| Status HTTP, autorização (`ROLE_USER`/`ROLE_ADMIN`/sem token), extração JWT | `user-service/.../controller/UserControllerTest.java`            | 37     |
| Endpoint interno `/internal/users/email/{email}` (200/404, acesso sem token) | `user-service/.../controller/InternalUserControllerTest.java`   | 3      |

> Usa `@Import({SecurityConfig.class, GlobalExceptionHandler.class})` — em Spring Boot 4.0 o slice `@WebMvcTest` não carrega essas classes automaticamente.

**Integração (Testcontainers — MongoDB `mongo:7` + Redis `redis:7-alpine`):**

| Foco                                                                             | Arquivo de teste                                            | Testes |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------- | ------ |
| Fluxo registro → busca → atualização → desativação/remoção e unicidade de e-mail | `user-service/.../integration/UserFlowIntegrationTest.java` | 17     |
| Comportamento do cache Redis (popular/evictar `usersById` e `usersByEmail`)      | `user-service/.../integration/CacheIntegrationTest.java`    | 14     |

> Base comum: `AbstractIntegrationTest` sobe os containers, mocka o `JwtDecoder` e limpa Redis (`flushDb`) + os caches `usersById`/`usersByEmail` entre os testes.

---

## Estratégia de Logs

Logging via SLF4J (`LoggerFactory.getLogger(Classe.class)`, `private static final LOGGER` por classe), sempre **parametrizado** (`{}`, nunca concatenação).

**Níveis (convenção):**

- `INFO` — eventos de fluxo de negócio bem-sucedidos (entrada de endpoint, registro, atualização, busca encontrada, `auth` enviando credenciais) e a requisição recebida no gateway
- `WARN` — anomalias esperadas e recuperáveis: e-mail já cadastrado, entidade não encontrada (404), argumento inválido (400), senha ausente, falha de login e rejeição por rate limit (429)
- `ERROR` — falhas inesperadas com stacktrace: handler genérico 500 e falha de comunicação Feign (authorization-server → user-service)
- `DEBUG` — alto volume / baixo valor operacional: operações de cache (`put`/`evict`) no `CacheService`

**Formato e convenções de escrita:** padrão em pipe, fácil de filtrar via grep. Estrutura `"| [VERBO_HTTP] | ação | campo: valor"`:

- Segmentos separados por ` | `; toda mensagem **começa** com `| `
- Verbo HTTP, quando presente, em **maiúsculas** (`POST`, `GET`, `PUT`, `DELETE`); ações de domínio em **minúsculas e infinitivo/pt-br** (`registrar`, `buscar`, `atualizar`, `desativação`, `deleção`)
- Chave de campo padronizada: `ID:` (sempre maiúsculo), `email:`, `nome:`, `motivo:`, `correlationId:`; múltiplos campos no mesmo segmento separados por `, ` (ex.: `nome: {}, ID: {}`)
- Pares simétricos sucesso/falha compartilham o mesmo prefixo de ação (ex.: `| busca por ID | encontrado` ↔ `| busca por ID | não encontrado`)
- Logs do fluxo de autenticação (em ambos os módulos) usam o namespace `| auth | ...` (`carregando usuário`, `enviando credenciais`, `login falhou`, `falha Feign user-service`, `inexistente ou inativo`)
- Sem texto em CAIXA ALTA em inglês e sem concatenação — sempre `{}` parametrizado

**Correlação entre serviços:** o `logging.pattern.level` (definido nos `*.yml` do config-server para user-service, gateway e authorization-server) inclui `traceId`/`spanId` do Micrometer — propagados via B3/Zipkin de ponta a ponta. O gateway também loga o `X-Correlation-ID` na borda. MDC não é usado no gateway (reativo) porque não propaga de forma confiável no WebFlux.

**PII / LGPD:** e-mails nunca são logados em claro. `LogUtils.maskEmail()` (um por módulo: `user-service/.../util/` e `authorization-server/.../util/`) mascara para `f***@dominio`. IDs de usuário (não-PII) são logados normalmente.

**Cobertura por classe (pontos logados):**

| Classe | Camada | Destaque |
| --- | --- | --- |
| `UserController` | user-service / controller | entrada dos 9 endpoints |
| `InternalUserController` | user-service / controller | entrada do auth interno (e-mail mascarado) |
| `RegisterService` | user-service / service | registro/update/desativar/deletar + rejeições (WARN) |
| `SearchService` | user-service / service | página/encontrado (INFO) + não encontrado (WARN) |
| `AuthenticationService` | user-service / service | `auth` — enviando credenciais (INFO) / inexistente ou inativo (WARN) |
| `CacheService` | user-service / service | put/evict dos 3 caches (DEBUG) |
| `GlobalExceptionHandler` | user-service / exceptions | 404/409/400 (WARN), 500 (ERROR), 403 relançado p/ Spring Security |
| `AuthorizationService` | authorization-server / service | `auth` — carregando usuário (INFO) + falha Feign user-service com stacktrace (ERROR) |
| `AuthFailureListener` | authorization-server / listeners | falhas de login via `AbstractAuthenticationFailureEvent` (WARN) |
| `CorrelationIdFilter` | gateway / filter | requisição recebida + `correlationId` |
| `JwtHeaderPropagationFilter` | gateway / filter | DEBUG quando não há JWT |
| `RateLimitLogFilter` | gateway / filter | rejeições 429 (WARN) |

> Nota: o handler `@ExceptionHandler(AccessDeniedException.class)` no `GlobalExceptionHandler` **relança** a exceção — sem ele, o catch-all `Exception` transformaria os 403 do `@PreAuthorize` em 500.

---

## Trabalho Pendente

> Reorganizado por tema/prioridade. Decisões de contexto: o sistema rodará em **múltiplas instâncias** (escala horizontal) e serve de **base/template reutilizável**. Ordem sugerida de execução: 1 → 2 → 3 → 4 → 5.

### 1. Escalabilidade horizontal (pré-requisito — hoje quebra com N instâncias)

- [ ] **Chaves JWK persistentes** no authorization-server — `JWKConfig.java` gera um par RSA novo a cada boot, em memória. Os resource servers validam via `issuer-uri`/JWKS; um JWT assinado pela instância A não valida na JWKS da instância B, e todo restart/deploy invalida os tokens vigentes. Carregar o par de chaves de keystore/secret externo (PEM ou JKS), com `kid` fixo compartilhado entre instâncias.
- [ ] **Persistir estado OAuth em DB relacional** — hoje `InMemoryRegisteredClientRepository` + os defaults `InMemoryOAuth2AuthorizationService`/`InMemoryOAuth2AuthorizationConsentService`. O `code` emitido por uma instância não pode ser trocado por token em outra. Migrar para `JdbcRegisteredClientRepository`, `JdbcOAuth2AuthorizationService` e `JdbcOAuth2AuthorizationConsentService` (schemas oficiais do Spring Authorization Server). Requer datasource novo (ex.: Postgres): `authorization-server/pom.xml` (`spring-boot-starter-jdbc` + driver), `docker-compose.yml` (serviço + volume), `config-server/.../authorization-server.yml` (`spring.datasource.*`)
- [ ] **Sessão HTTP compartilhada** — o login/consent do authorization-server e o `oauth2Login` + `ServerOAuth2AuthorizedClientRepository` do gateway dependem de sessão em memória. Adicionar Spring Session Data Redis (servlet no auth-server, reactive no gateway — que já tem Redis) ou sticky sessions no load balancer
- [ ] **Tratar `DuplicateKeyException` no registro → 409** — `RegisterService.registerUser` faz `findByEmail` e depois `insert`; entre instâncias concorrentes há corrida e o guard real é o índice único do Mongo. Hoje a corrida resultaria em 500; mapear no `GlobalExceptionHandler` para 409 (consistente com `EmailAlreadyRegisteredException`)
- [ ] **Deploy rolling sem downtime** — `server.shutdown=graceful` + readiness/liveness probes (actuator) em todos os serviços
- [ ] **(Infra) Eliminar SPOFs** — `discovery-server` em peer replication (Eureka HA), `config-server` replicado, MongoDB replica set e Redis Sentinel/Cluster

> Já adequados para escala: validação JWT stateless no resource server, rate limiter no Redis e os caches (`usersById`/`usersByEmail`/`authByEmail`) no Redis.

### 2. Resiliência e consistência

- [ ] Adicionar Resilience4j como circuit breaker na chamada Feign do authorization-server → user-service
  - `authorization-server/pom.xml` — adicionar `spring-cloud-starter-circuitbreaker-resilience4j`
  - Criar `clients/UserClientFallbackFactory.java` — `FallbackFactory<IUserClient>` que lança `UsernameNotFoundException` ao acionar o fallback
  - `clients/IUserClient.java` — adicionar `fallbackFactory = UserClientFallbackFactory.class` no `@FeignClient`
  - `services/AuthorizationService.java` — corrigir o `catch (Exception e)` que engole `UsernameNotFoundException` de usuário inativo junto com erros de comunicação
  - `config-server/.../authorization-server.yml` — habilitar `spring.cloud.openfeign.circuitbreaker.enabled=true` e adicionar bloco `resilience4j.circuitbreaker.instances.user-service` com `slidingWindowSize`, `failureRateThreshold`, `waitDurationInOpenState` e `permittedNumberOfCallsInHalfOpenState`
- [ ] **Handler para `@Valid` no `GlobalExceptionHandler`** — adicionar tratamento de `MethodArgumentNotValidException` no mesmo formato dos demais erros (idealmente `ProblemDetail`/RFC 7807); hoje sai no formato default do Spring, inconsistente
- [ ] **`permissions` derivadas das roles no `TokenCustomizerConfig`** — hoje `["users.read","users.write"]` é hardcoded para todo usuário; mapear roles → permissions (ex.: ADMIN ganha `users.delete`)

### 3. Segurança / hardening

- [ ] Proteger `InternalUserController` contra acesso direto à porta 8090 — expõe `passwordHash` e `roles` sem autenticação; adicionar validação por shared secret header (`X-Internal-Token`) em `SecurityConfig` ou restringir por IP. No authorization-server, um `RequestInterceptor` Feign injeta o header em toda chamada ao user-service
- [ ] **CORS configurável** — externalizar as origens hardcoded (`localhost:*`) dos `CORSConfig.java` dos 3 módulos para properties lidas do config-server
- [ ] **Secrets fora do `docker-compose.yml`** — mover credenciais do Mongo (`user_service:user_1234321`) e do Grafana (`admin/admin`) para `.env` git-ignored
- [ ] TLS/HTTPS — manter como gap conhecido (decidido: configurar com a infra de produção)

### 4. Front-end

- [ ] Reescrever `login-interface` como SPA redirect-based (authorization_code + PKCE). Obs.: o front hoje chama `POST /authentication/login` (token direto), endpoint que **não existe** no backend — está quebrado, não apenas incompatível

### 5. Fluxos futuros (não imediatos)

- [ ] Verificação de e-mail no cadastro
- [ ] Recuperação de senha
- [ ] **Gestão de admin** — decisão atual: criar/promover ADMIN manualmente no MongoDB (ex.: `db.users.updateOne({email: ...}, {$addToSet: {roles: "ADMIN"}})`). Sem automação por ora; sem isso as rotas `ROLE_ADMIN` ficam inalcançáveis

### Próximas camadas de domínio

- [ ] Outros microsserviços de negócio serão adicionados sobre esta base após o sistema de usuários estar estável

---

## Gaps de Segurança Conhecidos

Identificados e com decisão de abordagem registrada:

| Gap                                                          | Localização                             | Severidade | Decisão                                                                               |
| ------------------------------------------------------------ | --------------------------------------- | ---------- | ------------------------------------------------------------------------------------- |
| JWT armazenado em `localStorage` no front-end                | `login-interface/src/hooks/useLogin.ts` | Média      | Mantido por ora (SPA redirect-based). Avaliar cookies HttpOnly futuramente            |
| Grafana acessível com `admin/admin`                          | `docker-compose.yml`                    | Baixa      | Alterar credenciais e proteger o stack de observabilidade                             |
| Sem HTTPS/TLS                                                | Todo o sistema                          | Alta       | Decidido: configurar junto com a infraestrutura de produção                           |
| `InMemoryRegisteredClientRepository` no authorization-server | `OAuth2ClientConfig.java`               | Baixa      | Aceitável enquanto houver apenas um cliente (gateway). Migrar para JDBC se necessário |
