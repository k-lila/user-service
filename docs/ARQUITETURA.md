# Arquitetura

> **Escopo deste documento.** Aqui fica a **estrutura**: quais camadas existem dentro de cada
> serviço, como um request atravessa o sistema, quais contratos ligam os serviços e onde cada
> coisa mora no disco.
>
> O **contrato HTTP** (assinatura de endpoint, payloads de exemplo, schema de campo das coleções,
> chaves de cache) vive em [SERVICOS.md](SERVICOS.md) e **não é repetido aqui** — documento
> duplicado desatualiza, e este repositório já pagou por isso uma vez (o ADR-016 declarou um gap
> fechado consultando outro documento em vez do código, e a listagem pública sobreviveu seis
> semanas). Regra prática: **mudou um endpoint → edite `SERVICOS.md`**; este documento só muda
> quando muda uma camada, um contrato entre serviços ou o layout de arquivos.
>
> Visão geral e invariantes a preservar em [../CLAUDE.md](../CLAUDE.md) · execução em
> [../README.md](../README.md) · racional das convenções em [CONVENCOES.md](CONVENCOES.md) ·
> decisões formais em [adr/](adr/).

## Índice

**Parte I — Arquitetura da API back-end**

1. [Topologia de processos](#1-topologia-de-processos)
2. [Decisões estruturantes](#2-decisões-estruturantes)
3. [Anatomia comum de um serviço](#3-anatomia-comum-de-um-serviço)
4. [Serviço a serviço](#4-serviço-a-serviço)
5. [Contratos entre serviços](#5-contratos-entre-serviços)
6. [Fluxos ponta a ponta](#6-fluxos-ponta-a-ponta)
7. [Preocupações transversais](#7-preocupações-transversais)
8. [Propriedade dos dados](#8-propriedade-dos-dados)
9. [Mapa ADR → componente](#9-mapa-adr--componente)

**Parte II — [Árvore de arquivos anotada](#parte-ii--árvore-de-arquivos-anotada)**

---

# Parte I — Arquitetura da API back-end

## 1. Topologia de processos

Seis processos Spring, três stores, um SPA e a stack de observabilidade. As setas estão rotuladas
pelo **mecanismo real**, não pela intenção:

```
                          browser
                             │
                             │ HTTP (cookie SESSION / XSRF-TOKEN)
                             ▼
              ┌──────── login-interface (nginx :80) ────────┐
              │  SPA React + proxy same-origin de 9 paths   │
              └───────────────────┬─────────────────────────┘
                                  │ proxy_pass
                                  ▼
   ┌──────────────────────── gateway :8081 ────────────────────────┐
   │  WebFlux · cliente OAuth2 do BFF · CSRF · rate limit · rotas  │
   └──┬────────────────────────────┬───────────────────────────┬───┘
      │ lb:// (Eureka)             │ lb:// (Eureka)            │
      │ + tokenRelay()             │ (sem tokenRelay)          │ Lettuce
      ▼                            ▼                           ▼
 user-service :8090      authorization-server :8082      Redis Sentinel
   │        │                  │           │              (sessão do
   │        │ Feign            │ Feign     │ JDBC          gateway +
   │        │ (X-Internal-     │ (X-Int.-  │               rate limit +
   │        │  Token + B3)     │  Token)   │               epoch de
   │        ▼                  │           ▼               revogação)
   │  notification-service      │      auth-postgres :5432
   │       :8095 ──SMTP──▶      │      (estado OAuth2)
   │  (nunca exposto            │
   │   pelo gateway)            └──▶ Redis Sentinel (sessão AUTHSESSION,
   │                                  lockout, lock de purge, revogação)
   ├──▶ MongoDB rs0 (users · auditLogs · notificationOutbox)
   └──▶ Redis Sentinel (cache, rate limit de reenvio, lock do outbox,
                        ESCRITA do epoch de revogação)

 Plano de controle, fora do caminho do request:
   config-lb :8888 (nginx) ──▶ config-server (N réplicas, HTTP Basic)
   discovery-server-1 :9091  ·  discovery-server-2 :9092  (Eureka, peer replication)

 Observabilidade (scrape/push, nunca no caminho do request):
   prometheus :9090 ──scrape──▶ :8181 de cada app (dns_sd) + 3 exporters de infra
   grafana :3000 ──▶ prometheus        zipkin :9411 ◀──spans B3── todos os apps
```

**Portas.** Cada serviço de domínio expõe **duas**: a de tráfego e a de management. A separação
é o controle do gap G14 — o actuator saiu da porta pública e a 8181 **nunca é publicada** no
compose:

| Serviço | Tráfego | Management | Publicada em dev (override) | Stores próprios |
|---|---|---|---|---|
| `gateway` | 8081 | 8181 | `8081:8081` | Redis (sessão, rate limit) |
| `authorization-server` | 8082 | 8181 | `8082:8082` | PostgreSQL, Redis |
| `user-service` | 8090 | 8181 | `8090:8090` | MongoDB, Redis |
| `notification-service` | 8095 | 8181 | `8095:8095` | — (stateless; SMTP externo) |
| `config-server` | 8888 | — | via `config-lb` `8888:8888` | — |
| `discovery-server` 1/2 | 9091 / 9092 | — | `9091:9091` / `9092:9092` | — |

> A porta de tráfego é `${SERVER_PORT:...}` nos YAMLs servidos; a de management é **literal
> `8181`** nos quatro serviços de domínio. Ver
> `config-server/src/main/resources/config/*.yml`.

**Nada disso é publicado pela base do compose.** `docker-compose.yml` é prod-safe e não abre
porta alguma no host; quem publica é `docker-compose.override.yml` (dev) e
`docker-compose.deploy.yml` (só observabilidade, presa a `127.0.0.1`). Detalhes de topologia
executável e escala em [../README.md](../README.md) e
[BLUEPRINT.md § E](BLUEPRINT.md).

---

## 2. Decisões estruturantes

Cinco decisões explicam o formato de todo o resto. Quebrar qualquer uma reintroduz um bug já
resolvido:

| Decisão | O que impõe à estrutura | ADR |
|---|---|---|
| **BFF na borda** | O gateway é o cliente OAuth2 confidencial; o token vive na sessão do gateway e é injetado downstream por `tokenRelay()`. O SPA nunca vê JWT, não tem `/callback`, não faz PKCE, não usa `localStorage`. Consequência estrutural: o gateway tem `oauth2Login` + `oauth2Client` + resource server simultaneamente. | [ADR-002](adr/ADR-002-padrao-bff.md) |
| **Separação rígida de dados** | O `authorization-server` **não** acessa o MongoDB. Credenciais chegam por Feign ao user-service. O domínio de usuário tem um dono único; o auth-server é cliente. | [CONVENCOES.md](CONVENCOES.md) |
| **Canal interno isolado** | `/internal/**` existe em dois serviços, **fora** do gateway e **fora** do OpenAPI, guardado por shared secret `X-Internal-Token`. Não é "rota privada por convenção" — é filtro dedicado antes da cadeia de autenticação. | [ADR-006](adr/ADR-006-canal-interno-isolado.md) |
| **Config centralizada + discovery** | Nenhum serviço carrega sua config completa no jar: `spring.config.import=optional:configserver:` puxa de `config-server` (atrás de `config-lb`), e o roteamento é por nome lógico `lb://` resolvido pelo Eureka. Consequência: `config-server` sobe primeiro, sempre. | — |
| **Estado OAuth em PostgreSQL** | O auth-server é horizontalmente escalável porque `RegisteredClient`/`Authorization`/`Consent` são JDBC, não em memória. Consequência: seed idempotente do cliente + índice único + varredura de purga. | [ADR-003](adr/ADR-003-estado-oauth-postgresql.md), [ADR-022](adr/ADR-022-higiene-estado-persistente.md) |

Duas consequências dessas decisões que aparecem em vários pontos do código e valem registrar
uma vez só:

- **Autorização por role é sempre downstream.** O gateway roteia e aplica rate limit; ele
  **não** tem `hasRole()`. `ROLE_ADMIN` é checado exclusivamente por `@PreAuthorize` no
  `AdminController` ([ADR-014](adr/ADR-014-admin-controller-gestao-roles-auditoria.md)).
- **A revogação é um epoch compartilhado, não uma denylist.** Uma chave Redis por usuário,
  escrita por um serviço e lida por três ([ADR-017](adr/ADR-017-revogacao-ativa-token.md)) — ver
  [§5](#5-contratos-entre-serviços).

---

## 3. Anatomia comum de um serviço

Os quatro serviços de domínio repetem o mesmo gabarito de pacotes. O fluxo interno é sempre o
mesmo — **controller → service → repository → store** — com os pacotes de apoio ao redor:

```
com.users.<modulo>/
├── <Modulo>Application.java   # @SpringBootApplication + habilitadores explícitos
├── controller/                # camada HTTP: só orquestra e audita, sem regra de negócio
├── services/                  # regra de negócio, cache, transações lógicas, async
├── repository/                # acesso a dados (interfaces Spring Data)
├── domain/                    # entidades @Document e enums do domínio
├── dtos/                      # contrato de entrada/saída + validação Bean Validation
├── config/                    # segurança, cache, async, Feign, OpenAPI, filtros
├── clients/                   # interfaces Feign de saída + fallback factories
├── exceptions/                # exceções de domínio + @RestControllerAdvice
└── util/                      # utilitários puros (mascaramento de PII, resolução de IP)
```

Três fatos estruturais que fogem do gabarito e são fáceis de tropeçar:

**Não existe módulo `commons` nem POM agregador.** Cada módulo tem `pom.xml` independente e é
buildado sozinho (é assim que a matrix do CI roda). O preço é código **duplicado por cópia**
entre módulos:

| Classe | Onde vive duplicada |
|---|---|
| `ClientIpResolver` | `gateway/util/`, `authorization-server/util/` |
| `LogUtils` | `user-service/util/`, `authorization-server/util/` |
| `FeignConfig`, `FeignTracingConfig` | `user-service/config/`, `authorization-server/config/` |
| `InternalTokenFilter` | `user-service/config/`, `notification-service/config/` |
| `CORSConfig` | `gateway/config/`, `authorization-server/config/` |
| `GlobalExceptionHandler` | `user-service/exceptions/`, `notification-service/exceptions/` |
| `AuthDTO` | `user-service/dtos/`, `authorization-server/dtos/` |
| `EmailVerificationRequestDTO` | `user-service/dtos/`, `notification-service/dtos/` |

As oito são estado atual documentado, não recomendação. Ao alterar uma delas, **verifique a
gêmea** — nada no build acusa a divergência. (Os DTOs espelhados são caso à parte: são o
contrato entre serviços, e duplicá-los é deliberado — ver [§5](#5-contratos-entre-serviços).)

**Um desvio de convenção de pacote.** `authorization-server` usa a raiz `authorizationserver.*`;
todos os demais usam `com.users.<modulo>.*`.

**Habilitadores são explícitos no Spring Boot 4.0.** A autoconfiguração não dispara só pela
dependência estar no classpath — `@EnableRedisWebSession` (gateway),
`@EnableRedisHttpSession` (auth-server), `@EnableFeignClients`, `@EnableScheduling` e
`@EnableMethodSecurity` estão anotados à mão. Remover qualquer um quebra em runtime, não no
build.

---

## 4. Serviço a serviço

### 4.1 user-service — o dono do domínio

**Responsabilidade:** ciclo de vida do usuário (cadastro, perfil, desativação, remoção),
verificação de e-mail, gestão administrativa, trilha de auditoria LGPD e revogação de token.
É o único serviço que fala com o MongoDB.

**Dependências:** MongoDB (replica set `rs0`), Redis Sentinel, Eureka, config-server, JWKS do
auth-server (para validar o Bearer recebido), Feign → notification-service.
`spring.threads.virtual.enabled=true`.

**Camada HTTP — três controllers, três superfícies distintas:**

| Controller | Prefixo | Quem alcança | Guarda |
|---|---|---|---|
| `UserController` | `/v1/users` | público (via gateway) | `@PreAuthorize("hasRole('USER')")`, exceto `register` e `verify-email` |
| `AdminController` | `/v1/admin` | público (via gateway) | `@PreAuthorize("hasRole('ADMIN')")` em **todo** método |
| `InternalUserController` | `/internal/users` | só o auth-server, rede interna | `InternalTokenFilter` + `@Hidden` (fora do OpenAPI) |

A divisão é o próprio fix do IDOR de PII: `UserController` opera **exclusivamente sobre o
titular autenticado** (o id vem sempre de `jwt.getClaim("userID")`, nunca do path), e toda
leitura de terceiro migrou para o `AdminController`
([ADR-016](adr/ADR-016-leitura-pii-restrita-admin.md),
[ADR-021](adr/ADR-021-remocao-listagem-publica-usuarios.md)). Assinaturas em
[SERVICOS.md](SERVICOS.md#endpoints-expostos-via-gateway).

**Camada de serviço — 12 classes, agrupadas por papel:**

| Papel | Classes | Nota estrutural |
|---|---|---|
| CRUD do titular | `RegisterService`, `SearchService`, `AuthenticationService` | `SearchService`/`AuthenticationService` são os pontos `@Cacheable`; inativo é tratado como inexistente ([ADR-001](adr/ADR-001-leitura-somente-ativos.md)) |
| Superfície administrativa | `AdminService` | Único ponto que usa `MongoTemplate` direto (`Criteria` dinâmico para os filtros da listagem); os demais usam `MongoRepository` |
| Verificação de e-mail | `EmailVerificationService`, `VerificationTokenService`, `NotificationDispatchService`, `OutboxRetryService`, `ResendRateLimitService` | Padrão **outbox**: persiste primeiro, despacha depois, varre o que falhou ([ADR-015](adr/ADR-015-verificacao-email-cadastro.md)) |
| Auditoria | `AuditService` | Escrita `@Async` e isolada de falha — erro de auditoria nunca derruba a operação ([ADR-011](adr/ADR-011-trilha-auditoria-dado-pessoal.md)) |
| Segurança de token | `TokenRevocationService` | Escreve o epoch que outros três leitores consomem |
| Cache | `CacheService` | Evicção explícita nas mutações |

**Camada de dados:** três `MongoRepository` (`IUserRepository`, `IAuditLogRepository`,
`INotificationOutboxRepository`) sobre três `@Document` (`User`, `AuditLog`,
`NotificationOutbox`) e três enums (`AuditAction` — 13 valores, um `@Deprecated` mantido só
para desserializar histórico —, `NotificationStatus`, `NotificationType`).

**Mapeamento DTO:** não há classe mapper nem MapStruct — a conversão é método **estático no
próprio DTO** (`UserResponseDTO.toResponseDTO(User)`). Ao acrescentar um DTO, siga o padrão.

**Tratamento de erro — `GlobalExceptionHandler` (`@RestControllerAdvice` → `ProblemDetail`,
RFC 7807).** A regra não-óbvia: o advice **não** estende `ResponseEntityExceptionHandler`, e o
`ExceptionHandlerExceptionResolver` roda **antes** do `DefaultHandlerExceptionResolver`. Logo,
**toda exceção que o Spring traduziria sozinho precisa de handler explícito aqui** — sem ele cai
no catch-all e vira 500. É por isso que existem handlers para
`HttpRequestMethodNotSupportedException` (→405, o `GET /v1/users` removido) e
`NoResourceFoundException` (→404, os probes de `/actuator` na porta de tráfego). Ao acrescentar
advice novo, lembre-se disso.

**Configuração (`config/`):** `SecurityConfig` (resource server JWT, `@EnableMethodSecurity`,
CSRF off, **sem CORS** — o serviço nunca é chamado direto pelo browser, `InternalTokenFilter`
registrado antes do `BearerTokenAuthenticationFilter`), `CacheConfig` (3 caches Redis, TTL 5
min, serializer Jackson por tipo), `RevocationTokenValidator`, `MongoConfig`, `WebConfig`,
`OpenAPIConfig`, os dois configuradores de async (`AuditAsyncConfig`,
`NotificationAsyncConfig`) e os dois de Feign (`FeignConfig`, `FeignTracingConfig`).

> ⚠️ `"/actuator/**"` **tem de continuar** no `permitAll()` do `SecurityConfig` mesmo depois de o
> actuator ter ido para a 8181: a chain do contexto pai governa **também** a porta de management.
> Removê-la devolve 401 na própria 8181, o healthcheck derruba o container e o Prometheus para de
> raspar. O controle do G14 é a porta não ser publicada, não este matcher. Há comentário no
> arquivo dizendo isso.

### 4.2 authorization-server — o emissor de identidade

**Responsabilidade:** Authorization Server OAuth2/OIDC. Autentica por formulário, valida
credenciais obtidas do user-service, emite e customiza o JWT, guarda estado OAuth e aplica
lockout anti-brute-force.

**Dependências:** PostgreSQL (`authdb`), Redis Sentinel, Eureka, config-server, Feign →
user-service. **Sem virtual threads**, deliberadamente (pinning de JDBC/HikariCP no Java 21).

**Superfície HTTP:** os endpoints padrão do Spring Authorization Server, não escritos à mão —
`/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/oauth2/revoke`, `/oauth2/introspect`,
`/.well-known/*`, `/connect/logout`, `/userinfo` — mais `/login` e `/default-ui.css` do form
login. Não há `@RestController` neste serviço: a configuração **é** a API.

**Duas filter chains, e a ordem importa:**

| Ordem | Cobre | Papel |
|---|---|---|
| `@Order(1)` | endpoints do protocolo OAuth2/OIDC | `OAuth2AuthorizationServerConfigurer` + CORS + entry point que manda navegação `TEXT_HTML` para `/login` + **`AuthorizationEndpointRevalidationFilter`** (ADR-025) |
| `@Order(2)` | o resto | `permitAll` de `/oauth2/**`, `/.well-known/**`, `/login`, `/error`, `/actuator/**`; `formLogin` |

O filtro de re-derivação vive na `@Order(1)` **depois** do `SecurityContextHolderFilter` — antes
dele o `SecurityContextHolder` está vazio e o filtro vira no-op silencioso com build verde. E o
matcher dele é **positivo**, derivado de `AuthorizationServerSettings.getAuthorizationEndpoint()`:
a `@Order(1)` casa **todo** o `endpointsMatcher` do SAS (incluindo `/oauth2/token` e
`/connect/logout` via `oidc()`), então sem o recorte o filtro atuaria no back-channel e no logout.

**Configuração — as cinco classes que carregam decisão:**

- `OAuth2ClientConfig` — os três repositórios JDBC + o **seed idempotente** do `gateway-client`.
  O seed é um check-then-act e a concorrência o atravessa: quem impede a duplicata é o **índice
  único sobre `client_id`** no schema; o `seedGatewayClient` absorve `DuplicateKeyException`
  **e** `IllegalArgumentException` (o `assertUniqueIdentifiers` do SAS é ele próprio outro
  check-then-act) e relê o registro para confirmar que foi corrida
  ([ADR-022](adr/ADR-022-higiene-estado-persistente.md)). O bean do
  `JdbcOAuth2AuthorizationService` é **nomeado** para não colidir com o `UserDetailsService`.
- `JWKConfig` — par RSA carregado de PEM com `kid` estável, **não** gerado por boot (senão todo
  restart invalida os tokens vivos). A origem do PEM é parametrizável; as chaves não vivem no
  repositório ([ADR-005](adr/ADR-005-chave-jwk-persistente.md)).
- `TokenCustomizerConfig` — **arquivo crítico**. Emite `userID`, `roles`, `permissions` e
  `scope`, só no `access_token`, nunca no `id_token`. As coleções são serializadas como
  `ArrayList` de propósito (o `PolymorphicTypeValidator` do SAS rejeita `ImmutableCollections$*`
  na releitura do Postgres). No grant `refresh_token`, consulta o `RevocationRefreshGuard` e
  aborta com `invalid_grant` se a revogação for mais recente que o token apresentado.
- `SecurityConfig` — além das chains, o `CookieSerializer` que renomeia o cookie para
  **`AUTHSESSION`** (não colidir com o `SESSION` do gateway no salto front-channel) e o
  `redisNamespace` `authserver:session` ([ADR-007](adr/ADR-007-sessao-redis-cookies-distintos.md)).
- `FeignConfig` / `FeignTracingConfig` — injetam, respectivamente, o `X-Internal-Token` e os
  headers **B3**. O segundo existe porque a instrumentação automática registrava o span cliente
  no trace certo mas **não emitia os headers**, e o user-service abria um trace órfão.

**Serviços:** `AuthorizationService` (implementa `UserDetailsService`; converte o `AuthDTO`
recebido em `UserDetails`, monta as authorities `ROLE_*` + `USER_ID:{id}`, aplica o gate de
e-mail verificado com grace period e o `accountNonLocked` do lockout),
`LoginAttemptService` (contador Redis por par **(conta, IP)**), `OAuthStatePurgeService`
(`@Scheduled`, apaga autorizações expiradas em lote) e `RevocationRefreshGuard` (leitura do
epoch). Mais dois `@EventListener` (`LoginAttemptListener`, `AuthFailureListener`).

> ⚠️ O `catch` do `AuthorizationService` **tem** de ser `AuthenticationException` (o supertipo) e
> propagar sem reembrulhar: `UserServiceUnavailableException` não é subtipo de
> `UsernameNotFoundException` e cairia no `catch (Exception)`, regredindo em silêncio o fix que
> impede o lockout de contar indisponibilidade ([ADR-021](adr/ADR-021-remocao-listagem-publica-usuarios.md)).

### 4.3 gateway — a borda

**Responsabilidade:** único ponto de entrada externo. Cliente OAuth2 do BFF, roteamento, rate
limit, CSRF, logout RP-initiated, correlação de trace e rejeição antecipada de token revogado.

**Dependências:** Redis Sentinel (sessão + rate limiter + leitura do epoch), Eureka,
authorization-server (issuer). **É o único serviço reativo** (WebFlux) — o que muda o tipo de
tudo: `ServerHttpRequest` em vez de `HttpServletRequest`, `GlobalFilter` em vez de `Filter`,
`@EnableWebFluxSecurity`, `ReactiveStringRedisTemplate`.

**Tabela de roteamento** (`routing/GatewayRouter.java`, DSL Java com `RouteLocatorBuilder`) — é
a arquitetura da borda, e a ordem de declaração é significativa: rotas específicas precedem as
genéricas.

| Route id | Path | `tokenRelay()` | Tier / chave | Destino |
|---|---|---|---|---|
| `user-register` | `/v1/users/register` | — | LOW / IP | `lb://user-service` |
| `user-verify-email` | `/v1/users/verify-email` | — | LOW / IP | `lb://user-service` |
| `oauth` | `/oauth2/**` | — | MED / IP | `lb://authorization-server` |
| `auth-login` | `/login` | — | MED / IP | `lb://authorization-server` |
| `auth-default-ui` | `/default-ui.css` | — | LOW / IP | `lb://authorization-server` |
| `connect-logout` | `/connect/**` | — | MED / IP | `lb://authorization-server` |
| `user-service` | `/v1/users/**` | ✅ | HIGH / usuário | `lb://user-service` |
| `admin-service` | `/v1/admin/**` | ✅ | MED / usuário | `lb://user-service` |
| `user-service-docs` | `/v3/api-docs/user/**` | — | — | `lb://user-service` (com `rewritePath`) |
| `authorization-server-docs` | `/v3/api-docs/authorization-server/**` | — | — | `lb://authorization-server` (com `rewritePath`) |

Três regras que a tabela codifica e que não devem ser regredidas:

1. **`tokenRelay()` é por rota, nunca via `default-filters` do YAML** — a DSL Java não recebe
   default-filters.
2. **Rota sem sessão usa chave por IP, nunca por usuário.** `/login`, `/oauth2/**`,
   `/connect/**` e `/default-ui.css` chegam sem sessão; o `userKeyResolver` colapsaria todos os
   clientes no balde `"anonymous"` ([ADR-018](adr/ADR-018-rota-logout-front-channel-borda.md),
   [ADR-019](adr/ADR-019-correcao-elos-login-hostname-unico.md)).
3. **Tiers de mesmo nome não compartilham balde**: o `RedisRateLimiter` compõe a chave com o
   `routeId` (`request_rate_limiter.{routeId.ip}`), então `auth-default-ui` e `user-register`,
   ambos LOW, são independentes.

Os três buckets vivem em `RateLimiterConfig` — HIGH `(10, 20)` `@Primary`, MED `(5, 10)`, LOW
`(2, 5)` — junto dos dois `KeyResolver`.

**`SecurityConfig`:** `oauth2Login` (com resolver PKCE S256) + `oauth2Client` + resource server
JWT; CSRF por `CookieServerCsrfTokenRepository` (cookie `XSRF-TOKEN`, isento em
`/v1/users/register` e `/login`); `WebSessionIdResolver` com o cookie **`SESSION`** e
`redisNamespace` `gateway:session`; `oidcLogoutSuccessHandler` que monta o `id_token_hint`.
O ponto arquitetural é o **entry point híbrido**
([ADR-020](adr/ADR-020-swagger-atras-da-sessao.md)): `DelegatingServerAuthenticationEntryPoint`
devolve **401 para tudo** — premissa do BFF, já que o SPA é cliente JSON e decide quando logar —
**exceto** `/swagger-ui/**`, que recebe 302 por ser navegação de browser. `/v3/api-docs/**` fica
fora do redirect de propósito: é XHR, e um 302 para HTML faria o swagger-client parsear a tela
de login como JSON.

**Três `GlobalFilter`, com ordem relativa explícita:**

| Filtro | Ordem | Papel |
|---|---|---|
| `RateLimitLogFilter` | `HIGHEST_PRECEDENCE` | loga a decisão 429 com o IP resolvido |
| `RevocationWebFilter` | `HIGHEST_PRECEDENCE + 50` | lê o JWT da sessão, compara `iat` com o epoch no Redis → 401 + invalida a sessão. **Fail-open** |
| `CorrelationIdFilter` | (padrão) | semeia `X-Correlation-ID` a partir do traceId B3, fallback UUID |

O `RevocationWebFilter` existe porque o BFF autentica **por sessão**, não por bearer: a checagem
do resource server não pega o tráfego do SPA. O user-service continua sendo a camada
autoritativa; aqui o ganho é rejeição imediata na borda.

### 4.4 notification-service — o bounded context de notificação

Stateless: sem Mongo, sem Redis, sem Postgres, **sem Spring Security** e **sem springdoc**. Um
controller (`NotificationController`, `POST /internal/notifications/email-verification`), um
serviço (`EmailService` sobre `JavaMailSender`), um DTO, um `GlobalExceptionHandler` e o par
`InternalTokenFilter` + `InternalTokenFilterConfig` (`FilterRegistrationBean` mapeado em
`/internal/*`).

Três ausências deliberadas, cada uma com consequência de segurança:

- **Sem Spring Security** — não há outra rota autenticável; um `Filter` de servlet com
  comparação em tempo constante basta. Corolário: a **porta de management não publicada é o
  único controle** do actuator aqui. Não a publique.
- **Sem springdoc no `pom.xml`** — desligar por propriedade seria garantia *condicional* (a
  config vem do config-server com `optional:`); ausência de dependência é garantia de classpath
  ([ADR-021](adr/ADR-021-remocao-listagem-publica-usuarios.md)). **Não reintroduzir.**
- **Sem prazo numérico no corpo do e-mail** — o TTL vive no user-service e o DTO não o carrega;
  cravar "15 minutos" faria o texto mentir ao mudar a env.

### 4.5 config-server e discovery-server — o plano de controle

**config-server (8888)** — `@EnableConfigServer` em profile `native`, servindo
`classpath:/config`. Duas classes só (`ConfigServerApplication`, `SecurityConfig` com HTTP Basic
e `/actuator/health` aberto para o healthcheck). É **replicável por `--scale`** (stateless,
qualquer instância serve a mesma config) atrás do `config-lb`, um nginx que resolve por **DNS**.

> ⚠️ Não volte o `config-lb` ao bloco `upstream` estático: ele resolve os nomes no start e faz o
> nginx **recusar iniciar** se um não resolver — foi o que tornava o piso mínimo impossível
> ([ADR-024](adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md)).

Os cinco YAMLs em `config-server/src/main/resources/config/` são a **fonte única** da config dos
clientes; o `config-server` usa o próprio `application.yml` (não há `config-server.yml` servido).
Há um teste dedicado, `ServedConfigSecretLeakTest`, guardando vazamento de segredo nesses
arquivos.

**discovery-server (9091 / 9092)** — uma classe (`@EnableEurekaServer`). Dois nós em peer
replication, cada um registrando-se no outro via `EUREKA_PEER_URL`. A replicação de peer é
**push**: auto-referência quebra o piso HA.

---

## 5. Contratos entre serviços

Esta seção existe porque os contratos estão espalhados por seis módulos e **os quatro últimos
quebram em silêncio** — não há teste de compilação que os cubra.

**Contratos Feign (explícitos, tipados):**

| Consumidor → Provedor | Interface | Assinatura | Fallback | Guarda |
|---|---|---|---|---|
| auth-server → user-service | `authorizationserver.clients.IUserClient` | `GET /internal/users/email/{email}` → `AuthDTO` | `UserClientFallbackFactory` | `X-Internal-Token` + B3 |
| user-service → notification-service | `com.users.userservice.clients.INotificationClient` | `POST /internal/notifications/email-verification` → `void` | `NotificationClientFallbackFactory` | `X-Internal-Token` + B3 |

> ⚠️ O `UserClientFallbackFactory` **distingue duas causas**, porque o Feign entrega ambas pelo
> mesmo caminho: `FeignException.NotFound` (404 — titular inexistente/inativo) é resultado de
> negócio e vira `UsernameNotFoundException`, que **conta** no lockout; qualquer outra causa
> (500, 503, timeout, circuito aberto) é indisponibilidade e vira
> `UserServiceUnavailableException`, que **não** conta. Nunca use `instanceof FeignException`
> genérico nesse teste. Complemento obrigatório na config do circuit breaker:
> `ignoreExceptions: [feign.FeignException$NotFound]`, senão um 404 de negócio abre o circuito
> (DoS por typo). As duas coisas são interdependentes.

**Contratos implícitos (não tipados, quebram em runtime):**

| Contrato | Produtor | Consumidores | O que quebra se divergir |
|---|---|---|---|
| **Claims do JWT** — `userID`, `roles`, `permissions`, `scope` | `TokenCustomizerConfig` (auth-server) | `UserController`/`AdminController` (`userID`), `SecurityConfig.jwtAuthenticationConverter` (`roles` → `ROLE_`), `RevocationTokenValidator` e `RevocationWebFilter` (`userID` + `iat`) | renomear um claim derruba autorização ou revogação sem erro de compilação |
| **Epoch de revogação** — chave Redis `revoke:user:{userID}` | `TokenRevocationService` (user-service, **único escritor**) | `RevocationTokenValidator` (user-service), `RevocationRefreshGuard` (auth-server), `RevocationWebFilter` (gateway) | `security.revocation.key-prefix` diferente em um dos três = revogação silenciosamente inerte naquela camada |
| **Cookies e namespaces de sessão** | gateway (`SESSION` / `gateway:session`), auth-server (`AUTHSESSION` / `authserver:session`) | o browser, no salto front-channel | nomes iguais = uma sessão sobrescreve a outra; namespaces iguais = colisão no Redis |
| **DTOs espelhados** — `AuthDTO`, `EmailVerificationRequestDTO` | o provedor de cada rota | o consumidor Feign do outro lado | campo renomeado de um lado só = desserialização silenciosamente nula |

---

## 6. Fluxos ponta a ponta

### 6.1 Login (OAuth2 / BFF)

```
SPA → gateway /oauth2/authorization/gateway-client   (oauth2Login inicia)
  → browser redirecionado ao auth-server /oauth2/authorize
    → auth-server exibe /login (form) [+ /default-ui.css]
      → usuário submete credenciais
        → AuthorizationService.loadUserByUsername
          → IUserClient (Feign, X-Internal-Token + B3)
            → InternalUserController → AuthenticationService (@Cacheable authByEmail)
          ← AuthDTO (hash, roles, active, emailVerified, registrationDate)
        → gates, em ordem: lockout (accountNonLocked) → e-mail verificado
          (com grace de 24h) → BCrypt
        → TokenCustomizerConfig injeta userID/roles/permissions/scope
      ← código de autorização, redirect ao gateway
  → gateway troca código por token (back-channel interno) e guarda na sessão
← browser recebe só o cookie SESSION; o JWT nunca sai do gateway
```

Requests subsequentes: cookie `SESSION` → `tokenRelay()` injeta `Authorization: Bearer`
downstream. Logout: `POST /logout` encerra a sessão e dispara o RP-Initiated Logout via rota
`connect-logout`.

### 6.2 Cadastro e verificação de e-mail (outbox)

```
POST /v1/users/register  (LOW/IP, permitAll, CSRF isento)
  → RegisterService: persiste com emailVerified=false, consentAcceptedAt, termsVersion
  → AuditService.recordRegistration (@Async, não bloqueia a resposta)
← 201

  O cadastro NÃO dispara o e-mail. O envio é sempre ato explícito:

POST /v1/users/resend-verification (self) | /v1/admin/users/{id}/resend-verification (admin)
  → ResendRateLimitService (3/h por conta)
  → EmailVerificationService: token opaco, hash SHA-256 persistido em notificationOutbox (TTL 15m)
  → NotificationDispatchService (@Async, notificationExecutor)
      → INotificationClient (Feign + circuit breaker) → EmailService → SMTP
      ↳ falha ⇒ outbox FAILED, NUNCA propaga ao chamador
← 202

@Scheduled a cada 5m: OutboxRetryService (lock SETNX fail-CLOSED)
  → reprocessa FAILED/PENDING emitindo token NOVO (só o hash foi persistido — não há
    como remontar o link original)
  → para em: backoff não decorrido · titular inexistente · emailVerified ≠ false · teto de
    5 tentativas contado por REGISTROS do par (titular, tipo), não pelo campo attempts

GET /v1/users/verify-email?token=… (LOW/IP, pré-sessão)
  → confirma, idempotente; audita EMAIL_VERIFIED
```

### 6.3 Leitura autenticada com cache

```
SPA (cookie SESSION) → gateway rota user-service (HIGH/usuário) → tokenRelay()
  → user-service: RevocationTokenValidator (epoch) → JWT válido → ROLE_USER
    → UserController.me → SearchService (@Cacheable usersById, TTL 5min) → Mongo
```

Mutação inverte o sentido: `RegisterService` evicta os três caches **e** grava o epoch de
revogação, na mesma operação.

### 6.4 Revogação ativa (três camadas)

```
AdminService.updateUserRoles | RegisterService.deactivateUser | .deleteUser
  → TokenRevocationService.revoke(userID)  →  Redis revoke:user:{id} (TTL 75m)

Daí em diante, em paralelo:
  gateway      RevocationWebFilter        → 401 + invalida a sessão   (borda, imediato)
  user-service RevocationTokenValidator   → token rejeitado           (autoritativo)
  auth-server  RevocationRefreshGuard     → invalid_grant no refresh  (fecha a renovação)
  auth-server  AuthorizationEndpointRevalidationFilter
                                          → invalida a sessão do IdP  (fecha a EMISSÃO, ADR-025)
```

Todas são **fail-open**: outage de Redis não bloqueia autenticação. A invalidação não muta a sessão
viva — o re-login re-deriva roles e reaplica os gates de e-mail e `active`.

> **Corrigido em 2026-08-09 — não reintroduza a frase antiga.** Este parágrafo dizia que "a
> revogação **força re-autenticação**". Não força, e a diferença custou um incidente: as três
> checagens originais comparam `iat < epoch`, então enquanto a sessão do IdP vivesse o
> `/oauth2/authorize` reemitia credencial com `iat = agora` e **todas aprovavam por construção**
> (medido: nove `authorization_code` emitidos após um hard-delete, zero autenticações). O epoch
> torna a revogação eficaz sobre credenciais **já emitidas**; quem impede a emissão de novas é a
> quarta linha acima, a re-derivação do estado do titular na emissão
> ([ADR-025](adr/ADR-025-revalidacao-estado-emissao.md)). O inventário das sete cópias de estado de
> autorização, com dono e mecanismo de invalidação de cada uma, está em
> [CONVENCOES.md](CONVENCOES.md).

### 6.5 Operação administrativa auditada

```
SPA → gateway rota admin-service (MED/usuário, tokenRelay)
  → AdminController: @PreAuthorize("hasRole('ADMIN')")   ← única checagem de role do sistema
    → AdminService (MongoTemplate para os filtros da listagem)
    → AuditService.recordFromJwt | recordBulkFromJwt (@Async, MDC propagado)
       ↳ ADMIN_LIST_USERS grava UMA ENTRADA POR TITULAR da página, não uma agregada:
         com targetUserId nulo a listagem não apareceria no histórico de titular nenhum,
         e é essa a pergunta que a trilha responde (fix G13)
```

---

## 7. Preocupações transversais

**Segurança — cada serviço usa um mecanismo diferente, e a diferença é intencional:**

| Serviço | Mecanismo | Por quê |
|---|---|---|
| gateway | `@EnableWebFluxSecurity` + `oauth2Login`/`oauth2Client` + resource server | é o cliente OAuth2 confidencial do BFF |
| auth-server | `@EnableWebSecurity`, 2 chains (protocolo + form login) | é o servidor de autorização |
| user-service | resource server JWT + `@EnableMethodSecurity` | valida o Bearer relayado; autorização por role é aqui |
| notification-service | um `Filter` de servlet, sem Spring Security | só há a rota interna; a porta não publicada é o resto do controle |
| config-server | HTTP Basic | cliente é máquina; CSRF off |

**Cache (Redis, só no user-service):** `usersById`, `usersByEmail`, `authByEmail`, TTL 5 min,
com serializer Jackson por tipo (`CacheConfig`). Evicção explícita em toda mutação via
`CacheService`. Chaves de cache em [SERVICOS.md](SERVICOS.md#estratégia-de-cache-redis).

**Assíncrono e agendado — quatro pontos, com política de falha deliberadamente diferente:**

| Componente | Onde | Executor / gatilho | Política |
|---|---|---|---|
| `AuditService` | user-service | `auditExecutor` (MDC propagado por `TaskDecorator`) | isolado de falha: nunca derruba a operação |
| `NotificationDispatchService` | user-service | `notificationExecutor` | falha de envio nunca propaga ao cadastro/reenvio |
| `OutboxRetryService` | user-service | `@Scheduled` 5m + lock `SETNX` | **fail-CLOSED** |
| `OAuthStatePurgeService` | auth-server | `@Scheduled` 6h + lock `SETNX` | **fail-CLOSED** |

> A regra por trás da coluna "política": cache, rate limit e revogação são **fail-open** porque
> Redis fora não pode barrar autenticação. Os dois `@Scheduled` são **fail-closed** porque
> falhar aberto com N instâncias significaria N e-mails duplicados ou N deletes concorrentes por
> ciclo. Ao acrescentar um job agendado, decida conscientemente de que lado ele está.

**Observabilidade:** trace B3 ponta a ponta (incluindo os saltos Feign, via
`FeignTracingConfig`) exportado ao Zipkin; métricas em `/actuator/prometheus` na **8181**,
raspadas por `dns_sd_configs` (é o que dá **um target por réplica**); logs SLF4J parametrizados
em formato de pipe com `traceId`/`spanId` no MDC e PII mascarada por `LogUtils.maskEmail()`.
No gateway, o MDC só é populado com `spring.reactor.context-propagation: auto` — sem isso o log
da borda sai com `traceId=` vazio. Detalhes em [LOGS.md](LOGS.md) e no
[CLAUDE.md § Observabilidade](../CLAUDE.md).

**Resiliência:** os dois saltos Feign têm circuit breaker Resilience4j nomeado (`configs.*`, não
`instances.*` — com group habilitado este último é inerte) e fallback factory. Timeout 3s,
janela 10, threshold 50%, open 10s.

**Erros:** `ProblemDetail` (RFC 7807) via `@RestControllerAdvice` no user-service e no
notification-service. Formato em [SERVICOS.md](SERVICOS.md#formato-de-erros-rfc-7807--problemdetail).

---

## 8. Propriedade dos dados

Cada store tem **um dono**. Ninguém lê o store de outro serviço.

| Store | Dono | Conteúdo | Estrutura |
|---|---|---|---|
| **MongoDB** `rs0` | user-service | `users` (índice único em `email`), `auditLogs` (`target_ts_idx`, `ts_idx`, TTL *expire-at* por `purgeAt`), `notificationOutbox` (`userId_type_status`, `type_status_createdAt`, `tokenHash` único, TTL por `purgeAt`) | schemas em [SERVICOS.md](SERVICOS.md#schema-mongodb-coleção-users) |
| **PostgreSQL** `authdb` | authorization-server | `oauth2_registered_client` (+ índice único em `client_id`), `oauth2_authorization`, `oauth2_authorization_consent` | DDL em `authorization-server/src/main/resources/schema/` |
| **Redis** (Sentinel) | compartilhado por família de chave | cache do user-service · sessões (`gateway:session`, `authserver:session`) · rate limit do gateway · lockout do auth-server · epoch de revogação (1 escritor, 3 leitores) · locks dos dois `@Scheduled` | famílias em [SERVICOS.md](SERVICOS.md#outras-chaves-no-mesmo-redis-não-cache) |

Duas notas de retenção, ambas do [ADR-022](adr/ADR-022-higiene-estado-persistente.md): os TTL
do Mongo são **expire-at por documento** (`expireAfterSeconds=0` + campo `purgeAt`), não TTL
fixo no índice — TTL fixo prenderia a retenção ao valor vigente na criação e exigiria
`collMod`; e o `oauth2_authorization` **não tem índice para o predicado de purga**, de
propósito, porque o predicado é expressão sobre seis colunas e a própria purga mantém a tabela
pequena.

---

## 9. Mapa ADR → componente

Índice invertido de [adr/](adr/): dado um arquivo, quais decisões o governam. Consulte antes de
alterar.

| Componente | ADRs |
|---|---|
| `gateway/routing/GatewayRouter.java` | [002](adr/ADR-002-padrao-bff.md) · [015](adr/ADR-015-verificacao-email-cadastro.md) · [018](adr/ADR-018-rota-logout-front-channel-borda.md) · [019](adr/ADR-019-correcao-elos-login-hostname-unico.md) · [023](adr/ADR-023-smoke-test-automatizado-login-hostname-unico.md) |
| `gateway/config/SecurityConfig.java` | [002](adr/ADR-002-padrao-bff.md) · [007](adr/ADR-007-sessao-redis-cookies-distintos.md) · [019](adr/ADR-019-correcao-elos-login-hostname-unico.md) · [020](adr/ADR-020-swagger-atras-da-sessao.md) |
| `gateway/config/RateLimiterConfig.java`, `util/ClientIpResolver.java` | [010](adr/ADR-010-resolucao-ip-cliente-confiavel.md) |
| `gateway/filter/RevocationWebFilter.java`, `security/RevocationTokenReader.java` | [017](adr/ADR-017-revogacao-ativa-token.md) · [025](adr/ADR-025-revalidacao-estado-emissao.md) |
| `authorization-server/config/OAuth2ClientConfig.java` | [003](adr/ADR-003-estado-oauth-postgresql.md) · [022](adr/ADR-022-higiene-estado-persistente.md) |
| `authorization-server/config/JWKConfig.java` | [005](adr/ADR-005-chave-jwk-persistente.md) |
| `authorization-server/config/TokenCustomizerConfig.java` | [017](adr/ADR-017-revogacao-ativa-token.md) |
| `authorization-server/config/SecurityConfig.java` | [007](adr/ADR-007-sessao-redis-cookies-distintos.md) · [019](adr/ADR-019-correcao-elos-login-hostname-unico.md) · [025](adr/ADR-025-revalidacao-estado-emissao.md) |
| `authorization-server/filter/AuthorizationEndpointRevalidationFilter.java`, `session/AuthenticationInstantAttribute.java`, `listeners/AuthenticationInstantListener.java` | [025](adr/ADR-025-revalidacao-estado-emissao.md) |
| `authorization-server/services/AuthorizationService.java` | [015](adr/ADR-015-verificacao-email-cadastro.md) · [021](adr/ADR-021-remocao-listagem-publica-usuarios.md) · [025](adr/ADR-025-revalidacao-estado-emissao.md) |
| `authorization-server/services/LoginAttemptService.java`, `listeners/LoginAttemptListener.java` | [010](adr/ADR-010-resolucao-ip-cliente-confiavel.md) · [025](adr/ADR-025-revalidacao-estado-emissao.md) (o guard `instanceof UsernamePasswordAuthenticationToken` é load-bearing — não relaxe) |
| `authorization-server/services/OAuthStatePurgeService.java` | [022](adr/ADR-022-higiene-estado-persistente.md) |
| `authorization-server/clients/UserClientFallbackFactory.java` | [004](adr/ADR-004-resiliencia-feign-circuit-breaker.md) · [021](adr/ADR-021-remocao-listagem-publica-usuarios.md) |
| `user-service/controller/UserController.java` | [001](adr/ADR-001-leitura-somente-ativos.md) · [013](adr/ADR-013-remocao-rotas-admin-delete-user-controller.md) · [016](adr/ADR-016-leitura-pii-restrita-admin.md) · [021](adr/ADR-021-remocao-listagem-publica-usuarios.md) |
| `user-service/controller/AdminController.java` | [013](adr/ADR-013-remocao-rotas-admin-delete-user-controller.md) · [014](adr/ADR-014-admin-controller-gestao-roles-auditoria.md) · [016](adr/ADR-016-leitura-pii-restrita-admin.md) |
| `user-service/controller/InternalUserController.java` | [006](adr/ADR-006-canal-interno-isolado.md) |
| `user-service/services/RegisterService.java` | [012](adr/ADR-012-consentimento-lgpd-cadastro.md) · [015](adr/ADR-015-verificacao-email-cadastro.md) · [017](adr/ADR-017-revogacao-ativa-token.md) |
| `user-service/services/EmailVerificationService.java` + outbox | [015](adr/ADR-015-verificacao-email-cadastro.md) |
| `user-service/services/OutboxRetryService.java` | [015](adr/ADR-015-verificacao-email-cadastro.md) (emenda) · [022](adr/ADR-022-higiene-estado-persistente.md) |
| `user-service/services/AuditService.java`, `domain/AuditLog.java` | [011](adr/ADR-011-trilha-auditoria-dado-pessoal.md) · [014](adr/ADR-014-admin-controller-gestao-roles-auditoria.md) · [022](adr/ADR-022-higiene-estado-persistente.md) |
| `user-service/services/SearchService.java`, `AuthenticationService.java` | [001](adr/ADR-001-leitura-somente-ativos.md) |
| `user-service/services/TokenRevocationService.java`, `config/RevocationTokenValidator.java` | [017](adr/ADR-017-revogacao-ativa-token.md) |
| `notification-service/**` | [006](adr/ADR-006-canal-interno-isolado.md) · [015](adr/ADR-015-verificacao-email-cadastro.md) · [021](adr/ADR-021-remocao-listagem-publica-usuarios.md) |
| `login-interface/src/routes/router.tsx`, `nginx.conf` | [002](adr/ADR-002-padrao-bff.md) · [019](adr/ADR-019-correcao-elos-login-hostname-unico.md) |
| `docker-compose*.yml`, `infra/config-lb/`, `infra/mongo/rs-reconcile.sh` | [009](adr/ADR-009-base-secrets-native-docker-secrets.md) · [019](adr/ADR-019-correcao-elos-login-hostname-unico.md) · [024](adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md) |
| `infra/redis/sentinel.conf` | [008](adr/ADR-008-autenticacao-redis-sentinel.md) |
| `infra/secrets/gen-secrets.sh`, `infra/jwk/gen-keys.sh` | [005](adr/ADR-005-chave-jwk-persistente.md) · [009](adr/ADR-009-base-secrets-native-docker-secrets.md) |
| `infra/smoke-test/login-topology-smoke-test.sh` | [023](adr/ADR-023-smoke-test-automatizado-login-hostname-unico.md) |

---

# Parte II — Árvore de arquivos anotada

Monorepo de seis módulos Maven **independentes** (não há POM agregador na raiz), um SPA e a
infraestrutura. Podas aplicadas: `node_modules/`, `target/`, `.git/`, `dist/`, `coverage/`.

## Raiz

```
user-service/                          # raiz do monorepo (homônima do módulo de domínio)
├── CLAUDE.md                          # instruções e invariantes para agentes — o que preservar
├── README.md                          # pré-requisitos, execução, deploy, CI (público humano)
├── docker-compose.yml                 # BASE prod-safe: 19 serviços, NENHUMA porta publicada
├── docker-compose.override.yml        # deltas de dev (republica portas); auto-carregado no `up`
├── docker-compose.deploy.yml          # overlay Cloudflare Tunnel; observabilidade em 127.0.0.1
├── .env / .env.example                # contrato de variáveis (o .env real é gitignorado)
├── .dockerignore / .gitignore
├── secrets/                           # Docker secrets montados em /run/secrets/ — GITIGNORADO,
│                                      #   gerado por infra/secrets/gen-secrets.sh (ADR-009).
│                                      #   19 arquivos: credenciais, par JWK, SMTP_*, túnel
├── .vscode/settings.json
├── authorization-server/    · config-server/    · discovery-server/
├── gateway/                 · notification-service/  · user-service/
├── login-interface/                   # SPA React
├── infra/                             # configs e scripts de infraestrutura
├── docs/                              # esta documentação
├── .claude/                           # time de subagentes, skills, workflows, memória
└── .github/                           # CI
```

## `user-service/` — módulo de domínio

```
user-service/
├── Dockerfile · pom.xml
└── src/main/java/com/users/userservice/
    ├── UserServiceApplication.java          # @EnableFeignClients + @EnableScheduling
    ├── clients/                             # saída Feign → notification-service
    │   ├── INotificationClient.java
    │   └── NotificationClientFallbackFactory.java
    ├── config/
    │   ├── SecurityConfig.java              # resource server JWT + @EnableMethodSecurity
    │   ├── InternalTokenFilter.java         # guarda de /internal/** (ADR-006)
    │   ├── RevocationTokenValidator.java    # validador extra do JwtDecoder (ADR-017)
    │   ├── CacheConfig.java                 # 3 caches Redis, TTL 5min
    │   ├── MongoConfig.java · WebConfig.java · OpenAPIConfig.java
    │   ├── AuditAsyncConfig.java            # auditExecutor + TaskDecorator (MDC)
    │   ├── NotificationAsyncConfig.java     # notificationExecutor
    │   └── FeignConfig.java · FeignTracingConfig.java
    ├── controller/
    │   ├── UserController.java              # /v1/users — SÓ o titular autenticado
    │   ├── AdminController.java             # /v1/admin — todo método @PreAuthorize ADMIN
    │   └── InternalUserController.java      # /internal/users — @Hidden, fora do gateway
    ├── domain/
    │   ├── User.java                        # @Document("users")
    │   ├── AuditLog.java                    # @Document("auditLogs") + índice TTL
    │   ├── NotificationOutbox.java          # @Document("notificationOutbox") + índice TTL
    │   └── AuditAction.java · NotificationStatus.java · NotificationType.java
    ├── dtos/                                # + método estático toResponseDTO (não há mapper)
    │   ├── UserRequestDTO.java              # grupo OnCreate: termsAccepted (ADR-012)
    │   ├── UserResponseDTO.java · AdminUserResponseDTO.java   # o Admin expõe roles
    │   ├── AuthDTO.java                     # ESPELHADO no auth-server — contrato Feign
    │   ├── EmailVerificationRequestDTO.java # ESPELHADO no notification-service
    │   └── AuditLogResponseDTO.java · UpdateRolesRequestDTO.java
    ├── exceptions/
    │   ├── GlobalExceptionHandler.java      # ProblemDetail; precisa de handler EXPLÍCITO
    │   │                                    #   para tudo que o Spring traduziria sozinho
    │   ├── DomainEntityNotFound.java · EmailAlreadyRegisteredException.java
    │   └── InvalidVerificationTokenException.java · SelfRoleRevocationException.java
    ├── repository/
    │   └── IUserRepository.java · IAuditLogRepository.java · INotificationOutboxRepository.java
    ├── services/
    │   ├── RegisterService.java             # cadastro/update/soft/hard delete + revogação
    │   ├── SearchService.java               # @Cacheable usersById/usersByEmail
    │   ├── AuthenticationService.java       # @Cacheable authByEmail (serve o canal interno)
    │   ├── AdminService.java                # único uso de MongoTemplate (filtros dinâmicos)
    │   ├── AuditService.java                # @Async, isolado de falha
    │   ├── EmailVerificationService.java · VerificationTokenService.java
    │   ├── NotificationDispatchService.java # @Async → Feign
    │   ├── OutboxRetryService.java          # @Scheduled 5m, lock fail-CLOSED
    │   ├── ResendRateLimitService.java · TokenRevocationService.java
    │   └── CacheService.java
    └── util/LogUtils.java                   # maskEmail — PII nunca sai em claro no log
```

```
user-service/src/main/resources/application.yml    # só nome + import do config-server
user-service/src/test/
├── resources/application.yml
└── java/com/users/userservice/
    ├── config/          InternalTokenFilterTest · RevocationTokenValidatorTest
    ├── controller/      UserControllerTest · AdminControllerTest · InternalUserControllerTest
    ├── exceptions/      GlobalExceptionHandlerTest
    ├── integration/     AbstractIntegrationTest ← base Testcontainers do módulo
    │                    UserFlowIntegrationTest · AdminFlowIntegrationTest
    │                    AuditLogIntegrationTest · CacheIntegrationTest
    │                    EmailVerificationFlowIntegrationTest
    ├── services/        um *Test por service (12)
    └── util/            LogUtilsTest
```

## `authorization-server/`

```
authorization-server/
├── Dockerfile · pom.xml · .gitattributes · .mvn/wrapper/
└── src/main/java/authorizationserver/       # ⚠️ raiz de pacote SEM "com.users"
    ├── AuthorizationServerApplication.java
    ├── clients/
    │   ├── IUserClient.java                 # GET /internal/users/email/{email}
    │   ├── UserClientFallbackFactory.java   # distingue 404 (conta no lockout) de indisponibilidade
    │   └── UserServiceUnavailableException.java   # extends InternalAuthenticationServiceException
    ├── config/
    │   ├── SecurityConfig.java              # 2 chains + cookie AUTHSESSION
    │   ├── OAuth2ClientConfig.java          # repositórios JDBC + seed idempotente do client
    │   ├── JWKConfig.java                   # par RSA de PEM, kid estável (ADR-005)
    │   ├── TokenCustomizerConfig.java       # ⚠️ ARQUIVO CRÍTICO — claims do JWT
    │   ├── FeignConfig.java · FeignTracingConfig.java · CORSConfig.java
    ├── dtos/AuthDTO.java                    # ESPELHADO no user-service
    ├── filter/
    │   └── AuthorizationEndpointRevalidationFilter.java  # re-deriva o titular na EMISSÃO (ADR-025)
    │                                        #   NÃO é @Component: bean de Filter seria auto-registrado
    │                                        #   pelo Boot em TODO path, inclusive na porta de management
    ├── listeners/
    │   ├── LoginAttemptListener.java        # alimenta o contador de lockout
    │   ├── AuthenticationInstantListener.java  # carimba o instante de autenticação (ADR-025)
    │   └── AuthFailureListener.java         # só log
    ├── session/
    │   └── AuthenticationInstantAttribute.java  # Long epoch millis — tipo é requisito (ADR-025)
    ├── services/
    │   ├── AuthorizationService.java        # UserDetailsService: lockout → e-mail → BCrypt
    │   ├── LoginAttemptService.java         # contador Redis por (conta, IP)
    │   ├── OAuthStatePurgeService.java      # @Scheduled 6h, lock fail-CLOSED
    │   └── RevocationRefreshGuard.java      # epoch de revogação: refresh (ADR-017) + caminho
    │                                        #   degradado da re-derivação (ADR-025)
    └── util/ClientIpResolver.java · LogUtils.java
```

```
authorization-server/src/main/resources/
├── application.yml
├── keys/app.key · app.pub      # chaves de DEV; o diretório é GITIGNORADO (ADR-005)
└── schema/                     # DDL SAS 7.0.3 adaptado (blob→text, timestamp→timestamptz,
    ├── oauth2-registered-client-schema.sql        #   IF NOT EXISTS) + índice único client_id
    ├── oauth2-authorization-schema.sql
    └── oauth2-authorization-consent-schema.sql

authorization-server/src/test/java/authorizationserver/
├── clients/     UserClientFallbackFactoryTest
├── config/      TokenCustomizerConfigTest
├── filter/      AuthorizationEndpointRevalidationFilterTest
├── integration/ AbstractAuthIntegrationTest ← base do módulo
│                OAuth2AuthorizationCodeFlowIntegrationTest · LoginLockoutIntegrationTest
│                RedisSessionIntegrationTest · AuthsessionSecureCookieIntegrationTest
│                RegisteredClientSeedIntegrationTest · UserServiceCircuitBreakerIntegrationTest
│                AuthorizeRevalidationIntegrationTest · SessionMaxLifetimeIntegrationTest (ADR-025)
│                AuthorizationChainStructureIntegrationTest ← ordem do filtro na chain REAL
│                RevalidationKillSwitchIntegrationTest ← fio @Value→construtor do toggle
├── listeners/   LoginAttemptListenerTest · AuthenticationInstantListenerTest
├── services/    AuthorizationServiceTest · LoginAttemptServiceTest
│                OAuthStatePurgeServiceTest · RevocationRefreshGuardTest
└── util/        ClientIpResolverTest
```

## `gateway/`

```
gateway/
├── Dockerfile · pom.xml
└── src/main/java/com/users/gateway/
    ├── GatewayApplication.java
    ├── routing/GatewayRouter.java           # ⚠️ tabela de rotas; ordem de declaração importa
    ├── config/
    │   ├── SecurityConfig.java              # BFF: oauth2Login + client + resource server;
    │   │                                    #   CSRF; cookie SESSION; entry point híbrido
    │   ├── RateLimiterConfig.java           # 3 buckets + 2 KeyResolver
    │   └── CORSConfig.java · OpenAPIConfig.java   # ⚠️ sem bloco springdoc oauth (ADR-020)
    ├── filter/
    │   ├── RateLimitLogFilter.java          # HIGHEST_PRECEDENCE
    │   ├── RevocationWebFilter.java         # HIGHEST_PRECEDENCE + 50; fail-open
    │   └── CorrelationIdFilter.java         # X-Correlation-ID = traceId B3
    ├── security/
    │   └── RevocationTokenReader.java       # lê userID/iat verificando assinatura e IGNORANDO exp
    │                                        #   ⚠️ NUNCA é bean de ReactiveJwtDecoder: desligaria a
    │                                        #   autoconfig e o resource server aceitaria bearer
    │                                        #   expirado (@ConditionalOnMissingBean) — ADR-025
    └── util/ClientIpResolver.java           # reativo (ServerHttpRequest) — gêmeo do auth-server
```

```
gateway/src/test/java/com/users/gateway/
├── GatewayApplicationTests
├── config/      SecurityConfigBeansTest · RateLimiterConfigTest · CORSConfigTest · OpenAPIConfigTest
├── filter/      CorrelationIdFilterTest · RateLimitLogFilterTest · RevocationWebFilterTest
├── integration/ AbstractGatewayIntegrationTest ← base do módulo
│                GatewayRoutingIntegrationTest · GatewaySecurityIntegrationTest
│                GatewayOAuth2FlowIntegrationTest · GatewayAdminRouteIntegrationTest
│                RateLimitIntegrationTest · XForwardedHeadersIntegrationTest
│                JwtDecoderStrictnessIntegrationTest ← bearer expirado segue 401 (ADR-025);
│                  NÃO herda a base, que mocka o ReactiveJwtDecoder
├── security/    RevocationTokenReaderTest
└── util/        ClientIpResolverTest
```

## `notification-service/`

```
notification-service/
├── Dockerfile · pom.xml                     # ⚠️ SEM springdoc — não reintroduzir (ADR-021)
└── src/main/java/com/users/notificationservice/
    ├── NotificationServiceApplication.java
    ├── config/
    │   ├── InternalTokenFilter.java         # gêmeo do user-service; sem Spring Security aqui
    │   └── InternalTokenFilterConfig.java   # FilterRegistrationBean em /internal/*
    ├── controller/NotificationController.java    # POST /internal/notifications/email-verification
    ├── dtos/EmailVerificationRequestDTO.java     # ESPELHADO no user-service
    ├── exceptions/EmailSendFailedException.java · GlobalExceptionHandler.java
    └── services/EmailService.java           # JavaMailSender; corpo SEM prazo numérico

notification-service/src/test/java/com/users/notificationservice/
└── config/InternalTokenFilterTest · controller/NotificationControllerTest · services/EmailServiceTest
```

Não há `src/test/resources` — o serviço é stateless e não tem teste de integração próprio.

## `config-server/` e `discovery-server/`

```
config-server/
├── Dockerfile · pom.xml
└── src/main/
    ├── java/com/users/configserver/
    │   ├── ConfigServerApplication.java     # @EnableConfigServer, profile native
    │   └── config/SecurityConfig.java       # HTTP Basic; /actuator/health aberto p/ healthcheck
    └── resources/
        ├── application.yml                  # config do PRÓPRIO config-server
        └── config/                          # ⚠️ FONTE ÚNICA da config dos 5 clientes
            ├── gateway.yml · authorization-server.yml · user-service.yml
            └── notification-service.yml · discovery-server.yml
config-server/src/test/java/com/users/configserver/
└── ConfigServerApplicationTests · ConfigServerSecurityTest · ServedConfigSecretLeakTest
                                                              ↑ guarda vazamento de segredo

discovery-server/
├── Dockerfile · pom.xml
└── src/main/java/com/users/discoveryserver/DiscoveryServerApplication.java   # @EnableEurekaServer
```

## `login-interface/` — SPA React

```
login-interface/
├── Dockerfile · nginx.conf                  # ⚠️ proxy same-origin de 9 paths ao gateway,
│                                            #   INCLUINDO /login (ADR-019). Guardado pelo smoke-test
├── vite.config.ts                           # proxy de DEV: só 4 paths (em dev o browser vai
│                                            #   direto ao :8082 no front-channel)
├── vitest.config.ts · tsconfig*.json · eslint.config.js · .prettierrc
├── index.html · package.json · .envexample · README.md
└── src/
    ├── main.tsx · App.tsx
    ├── api/          apiAxios.ts (withCredentials, SEM Authorization) · authClient.ts · userClient.ts
    ├── components/   LoginBox · RegisterBox · ProfileBox · NavBar · ProtectedLayout
    ├── hooks/        useCurrentUser.ts (estado de auth = GET /v1/users/me 200 vs 401) · useRegister.ts
    ├── pages/        Login.tsx (mora em "/") · Register.tsx · Dashboard.tsx
    ├── routes/       router.tsx    # ⚠️ NÃO tem rota /login — o path pertence ao IdP (ADR-019)
    └── test/         setup.ts · server.ts (MSW) · handlers.ts · utils.tsx
```

Convenção do front: **testes co-locados** (`X.tsx` + `X.test.tsx` lado a lado), não em pasta
espelho como no back-end. A camada de acesso HTTP chama-se `api/`, não `services/`.

## `infra/`

```
infra/
├── prometheus.yml                      # alvos: dns_sd na 8181 + 3 exporters + config-server (Basic)
├── secrets/gen-secrets.sh              # gera ./secrets/ — PRÉ-REQUISITO do 1º up (ADR-009)
├── jwk/gen-keys.sh                     # gera o par RSA de assinatura do JWT (ADR-005)
├── config-lb/nginx.conf                # LB do config-server: resolver DNS + proxy_pass sobre
│                                       #   variável. ⚠️ não voltar ao bloco upstream estático
├── mongo/
│   ├── rs-reconcile.sh                 # rs.reconfig ADITIVO; descobre membros por DNS (ADR-024)
│   ├── create-admin.sh
│   └── keyfile                         # keyfile de DEV, versionado — gap conhecido
├── redis/sentinel.conf                 # base; requirepass/auth-pass injetados em runtime (ADR-008)
├── grafana/
│   ├── dashboards/                     # HTTP · JVM · Mongo · Postgres · Redis
│   │                                   #   ⚠️ threshold que assume o topo da escala é bug (ADR-024)
│   └── provisioning/dashboards/ · datasources/
├── cloudflared/config.yml              # ingress rules versionadas (sem hostname nem tunnel id)
└── smoke-test/login-topology-smoke-test.sh   # 5 asserções da cadeia nginx→gateway→auth (ADR-023)
```

## `docs/`, `.claude/` e `.github/`

```
docs/
├── ARQUITETURA.md   ← este documento: estrutura, fluxos, contratos, árvore
├── SERVICOS.md      # contrato da API: endpoints, payloads, schemas, cache
├── CONFIG.md        # variáveis de ambiente, Docker secrets, limites de recursos
├── CONVENCOES.md    # invariantes de design e o porquê de cada uma
├── SECURITY.md      # controles ativos, gaps abertos, dívida aceita
├── TESTES.md        # estratégia de testes, gate de cobertura, smoke-test
├── LOGS.md          # formato, níveis, mascaramento de PII
├── BLUEPRINT.md     # genérico vs. específico do domínio + eixos de escala
├── ORQUESTRACAO.md  # protocolo do time de subagentes
└── adr/             # ADR-001..024 + TEMPLATE.md — decisões formais, em ordem

.claude/
├── agents/          # 7 papéis: product-manager, senso-critico, techlead, qa-tester,
│                    #   security-reviewer, dependency-steward, report-writer
├── skills/          # 5 skills de conhecimento + 5 invocáveis (check-compat, new-adr,
│                    #   security-scan, suggest-tests, write-readme)
├── workflows/       # feature · bugfix · hotfix · new-service · dependency-update
├── memory/          # context.json · decisions.md · blockers.md (estado entre sessões)
└── settings.local.json

.github/
├── workflows/ci.yml # matrix backend (mvn verify + gate JaCoCo) · frontend · compose-validate
│                    #   · smoke-test-login. A main exige todos verdes (branch protection)
└── modernize/java-upgrade/hooks/scripts/    # hooks auxiliares de upgrade
```

---

## Convenções de layout a preservar

1. **Pacote base por módulo** — `com.users.<modulo>` para todos, exceto o `authorization-server`
   (`authorizationserver`). Ao criar classe nova, siga o pacote do módulo em que ela nasce.
2. **Testes em pasta espelho no back-end** (`src/test/java/<mesmo pacote>/`), **co-locados no
   front** (`X.tsx` ao lado de `X.test.tsx`).
3. **Uma base de integração por módulo** — `AbstractIntegrationTest` (user-service),
   `AbstractAuthIntegrationTest`, `AbstractGatewayIntegrationTest`. Teste de integração novo
   estende a base existente; não suba Testcontainers avulso.
4. **`config-server/src/main/resources/config/` é a fonte única de config servida.** O
   `application.yml` de cada módulo carrega apenas o nome da aplicação e o
   `spring.config.import`. Não duplique propriedade servida no `application.yml` local.
5. **DTO converte a si mesmo** por método estático; não há camada de mapper.
6. **Classe duplicada entre módulos tem gêmea** — ver a tabela em
   [§3](#3-anatomia-comum-de-um-serviço). Alterou uma, verifique a outra: o build não acusa.
7. **Ao acrescentar um serviço**, o gabarito mínimo é `Dockerfile` + `pom.xml` independente +
   `application.yml` com `spring.config.import` + entrada em
   `config-server/.../config/<servico>.yml` + registro no Eureka + alvo no `infra/prometheus.yml`
   (porta de management **8181**, nunca a de tráfego) + `management.server.port: 8181`. O
   workflow completo está em `.claude/workflows/new-service.md`.
