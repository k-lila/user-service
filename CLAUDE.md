# CLAUDE.md

## Restrições de Execução

- Não refatore código fora do escopo explícito da tarefa pedida
- Não adicione tratamento de erro para cenários impossíveis
- Não crie arquivos sem ser pedido explicitamente
- Pergunte antes de agir se a tarefa tiver mais de 3 arquivos envolvidos
- Antes de cada alteração no código, apresente um relatório que aponte claramente: 1) razões dos novos códigos e/ou das modificações; 2) arquivos a serem criados, se houver; 3) arquivos a serem modificados, se houver
- Ao criar uma nova branch, pergunte seu nome
- Sempre que elaborar perguntas e fornecer as opções de resposta, sempre dê um panorama simples e resumido sobre a opção em si, com seus prós e contras.

---

## Visão Geral do Projeto

Sistema de microsserviços em Java + Spring para gerenciamento de usuários — a **v1 do blueprint
de um sistema de usuários**, estável e pronta para produção. Autenticação, registro, perfil e
controle de acesso funcionam **ponta a ponta**, e essa base é a **fundação sobre a qual novas
camadas de domínio são construídas**. A evolução é ativa; o cuidado é que **cada nova
implementação seja segura e compatível** com o que já existe — invariantes de design, contratos
entre serviços e controles de segurança preservados, com os controles de qualidade (testes, gate
de cobertura, observabilidade) sempre saudáveis.

O front-end React (`login-interface`) usa o padrão **BFF**: o gateway é o cliente OAuth2, o SPA
usa sessão por cookie e **não** manuseia JWT.

**Mapa de documentos:**

- [README.md](README.md) — pré-requisitos e execução (humano)
- [docs/SERVICOS.md](docs/SERVICOS.md) — referência da API (endpoints, schema MongoDB, cache)
- [docs/CONFIG.md](docs/CONFIG.md) — variáveis de ambiente
- [docs/CONVENCOES.md](docs/CONVENCOES.md) — convenções e invariantes de design
- [docs/TESTES.md](docs/TESTES.md) — estratégia de testes
- [docs/LOGS.md](docs/LOGS.md) — estratégia de logs
- [docs/SECURITY.md](docs/SECURITY.md) — controles ativos e gaps de segurança conhecidos
- [docs/ORQUESTRACAO.md](docs/ORQUESTRACAO.md) — sistema de orquestração de agentes
- [docs/adr/](docs/adr/) — Architecture Decision Records (template em `docs/adr/TEMPLATE.md`); criados pelo `techlead` em mudanças de contrato/schema. Registrados: **ADR-001** leitura somente de ativos · **ADR-002** padrão BFF · **ADR-003** estado OAuth em PostgreSQL · **ADR-004** resiliência Feign (circuit breaker) · **ADR-005** chave JWK persistente · **ADR-006** canal interno isolado · **ADR-007** sessão Redis + cookies distintos · **ADR-008** autenticação no Redis/Sentinel · **ADR-009** base secrets-native (Docker secrets) · **ADR-010** resolução de IP do cliente confiável (CF-Connecting-IP + forward-headers)

---

## Arquitetura

```
login-interface (React)
        │
        ▼
    gateway :8081          ← único ponto de entrada externo
    ├── /v1/users/register  → user-service
    ├── /oauth2/**          → authorization-server
    ├── /login              → authorization-server
    └── /v1/users/**        → user-service
        │
        ├── authorization-server :8082
        │       ├── chama user-service via Feign (circuit breaker Resilience4j + UserClientFallbackFactory)
        │       ├── PostgreSQL (auth-postgres :5432 — estado OAuth: client/authorizations/consents)
        │       └── Redis Sentinel (sessão de login/consent)
        │
        ├── user-service :8090
        │       ├── MongoDB replica set rs0 (mongo-1/2/3 — persistência)
        │       └── Redis Sentinel (cache + rate limiting)
        │
        ├── discovery-server-1 :9091 · discovery-server-2 :9092  (Eureka HA — peer replication)
        ├── config-lb :8888 → config-server-1 · config-server-2  (Config HA — nginx upstream)
        ├── zipkin :9411 · prometheus :9090 · grafana :3000
```

**Tecnologias:**

- **Back-end:** Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.0, Maven
- **Dados:** MongoDB (dados de usuário), PostgreSQL (estado OAuth), Redis (cache, rate limiting e sessão)
- **Front-end:** React 19 + TypeScript + Vite + TailwindCSS 4
- **Orquestração:** Docker Compose

### Fluxo de Autenticação (OAuth2 / BFF)

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

**Claims customizados no JWT:** `userID` (ID no MongoDB), `roles` (ex. `["USER"]`), `permissions` (`["users.read","users.write"]`).

---

## Desenvolvimento local

### Subir tudo com Docker

**Pré-requisito obrigatório (uma vez):** a base é **secrets-native** ([ADR-009](docs/adr/ADR-009-base-secrets-native-docker-secrets.md)) — sem `./secrets/` o `up` **falha**. Gere os segredos + o par JWK antes do primeiro `up`:

```bash
infra/secrets/gen-secrets.sh   # defaults de DEV; em prod exporte cada segredo com valor forte
docker compose up -d --build
```

O `docker-compose.yml` é **base prod-safe** (publica só `gateway:8081` e `interface`); o `docker-compose.override.yml` (auto-carregado por `docker compose up`) republica as portas internas para **dev**. Para um deploy prod-like (só a borda exposta), rode `docker compose -f docker-compose.yml up` (ignora o override) com um `.env` setando as URLs públicas — ver `.env.example`. Todos os 26 serviços declaram `cpus` / `mem_limit` / `mem_reservation` (chaves de nível de serviço, não `deploy.resources`) — perfis e racional em [docs/CONFIG.md § Limites de recursos](docs/CONFIG.md#limites-de-recursos-cpu--memória).

**Segredos (base secrets-native, gap 0.3 RELATORIOA):** os segredos saem do `.env` plano para **Docker secrets** em `./secrets/` (gitignorado, gerados por `infra/secrets/gen-secrets.sh`), montados em `/run/secrets/`. Consumo: Spring via `spring.config.import=configtree:/run/secrets/` (nome do arquivo = placeholder); postgres/mongo via `*_FILE`; redis/sentinel via `$(cat ...)`; redis-exporter via `--redis.password-file` (JSON); grafana via `__FILE`; prometheus via `password_file`. O par JWK também é secret (`jwk_private`/`jwk_public`). Mecanismos e tabela em [docs/CONFIG.md § Docker secrets](docs/CONFIG.md#docker-secrets-base-secrets-native).

**TLS/HTTPS na borda em dev (opcional):** overlay opt-in `docker-compose.tls.yml` sobe um reverse-proxy nginx (`infra/tls-proxy`) que termina TLS com cert do **mkcert** e fala HTTPS com o browser (`app.localhost`/`auth.localhost`), mantendo o interno em HTTP. `docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build`. O Swagger-UI segue funcional nesse modo: o fluxo OAuth2 passa pela borda (`AUTH_URL`/`AUTH_TOKEN` → `https://auth.localhost`; CORS do auth-server inclui `http://localhost:8081`) — a porta 8082 não é publicada sem o override de dev.

**Deploy via Cloudflare Tunnel (opcional):** overlay `docker-compose.deploy.yml` sobe o `cloudflared` (quick tunnel → `gateway:8081`) com `APP_COOKIE_SECURE=true`, `SERVER_FORWARD_HEADERS_STRATEGY=framework` e CORS/URLs front-channel via `${TUNNEL_ORIGIN}`. Quick tunnel **valida** a mecânica de borda; a URL é efêmera (não cruza a barra de deploy real — exige named tunnel + domínio). Roteiro de subida (boot com placeholder → ler URL → re-up `--no-deps` sem recriar o cloudflared) no cabeçalho do próprio arquivo e em [README § 2c](README.md). O doc OpenAPI do Swagger requer `API_BASE_URL`/`AUTH_URL` no **user-service** apontando ao túnel, senão o "Try it out" dispara para `localhost` (mixed content).

### Ordem manual (sem Docker)

1. config-server → 2. discovery-server → 3. authorization-server → 4. user-service → 5. gateway (`mvn spring-boot:run` em cada) → 6. login-interface (`npm run dev`)

Variáveis de ambiente: ver [docs/CONFIG.md](docs/CONFIG.md). Em dev manual do BFF, exporte `OAUTH_REDIRECT_URI=http://localhost:5173/login/oauth2/code/gateway-client`.

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

- **Zipkin** — B3; sampling default dev 100% (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY:1.0` nos `*.yml` do config-server); reduza via env em prod (ex.: `0.1`); storage default `mem` (in-memory, traces somem no restart), parametrizável para Elasticsearch externo via `ZIPKIN_STORAGE_TYPE`/`ZIPKIN_ES_*` (ver `docs/CONFIG.md`)
- **Prometheus** — `/actuator/prometheus`, scrape 5s; job `microservices` (discovery×2, auth-server, user-service, gateway) + job `config-server` (config-server-1/2:8888, `basic_auth` com credenciais default dev `config-client`/`config-dev-secret` — o `/actuator/prometheus` do config-server fica atrás de HTTP Basic; migrar para `password_file` em prod); 3 exporters de infra (sem `ports:` no base — prod-safe):
  - `mongodb-exporter:9216` (percona/mongodb_exporter:0.43.1) — RS via seed único `mongo-1:27017` + `replicaSet=rs0`; credencial via env (`MONGODB_URI`)
  - `postgres-exporter:9187` (prometheuscommunity/postgres-exporter:v0.16.0) — `DATA_SOURCE_NAME` para `auth-postgres:5432/authdb`
  - `redis-exporter:9121` (oliver006/redis_exporter:v1.62.0) — modo multi-target (`/scrape`): 3 data nodes (`redis-1/2/3:6379`) + 3 sentinels (`redis-sentinel-1/2/3:26379`); relabel `__address__`→`instance` (sem colisão)
- **Grafana** — 5 dashboards pré-provisionados (`dashboardHTTP.json` — HTTP de borda; `dashboardJVM.json` — heap/non-heap/GC/threads/CPU, template var `application` com os 5 serviços via `label_values(jvm_memory_used_bytes, application)`; `dashboardMongo.json` — estado do RS/conexões/opcounters/memória/rede; `dashboardPostgres.json` — up/conexões/transações/deadlocks/cache hit ratio/tamanho; `dashboardRedis.json` — nós up/memória/hit ratio/comandos/Sentinel); todos referenciam o datasource por nome `"Prometheus"` (consistente com o provider `file` do provisioning)
- **SLOs** — 50ms / 100ms / 200ms / 500ms / 1s / 2s

---

## Serviços

> Referência da API (endpoints, schema, cache) em [docs/SERVICOS.md](docs/SERVICOS.md). Aqui ficam só as responsabilidades não-óbvias e os arquivos críticos a preservar.

### config-server (8888)

- **Config centralizada** via `classpath:/config`; os demais serviços importam com `spring.config.import=optional:configserver:${CONFIG_SERVER_URL}`.
- Arquivos em `config-server/.../config/{servico}.yml`.
- **HA:** duas instâncias (`config-server-1`, `config-server-2`) atrás de `config-lb` (nginx). `CONFIG_SERVER_URL` aponta para `config-lb:8888`. Stateless — qualquer instância serve a mesma config.
- **Deve subir primeiro** — todos os demais dependem dele via `config-lb`.
- **HTTP Basic:** `SecurityConfig` exige autenticação no endpoint (`/actuator/health` aberto p/ healthchecks; CSRF off — cliente é máquina). Os clientes enviam `spring.cloud.config.username/password`; par único `CONFIG_SERVER_USERNAME`/`CONFIG_SERVER_PASSWORD` (default dev no `application.yml`, sem default no compose → fail-fast).

### discovery-server (9091 / 9092)

- **Netflix Eureka HA** — dois nós em peer replication (`discovery-server-1:9091`, `discovery-server-2:9092`); cada instância se registra na outra via `EUREKA_PEER_URL`.
- `EUREKA_URI` nos demais serviços lista ambas as instâncias (CSV).
- Sobe após `config-lb`.

### authorization-server (8082)

- **Tipo:** OAuth2 Authorization Server (Spring Security). Fluxo authorization_code + PKCE + refresh_token; OIDC (`openid`, `profile`).
- **Estado OAuth em PostgreSQL** (escala horizontal):
  - `JdbcRegisteredClientRepository` + `JdbcOAuth2AuthorizationService` + `JdbcOAuth2AuthorizationConsentService` (`OAuth2ClientConfig.java`).
  - `gateway-client` **semeado idempotentemente** na subida (`findByClientId` → `save`), preservando `redirectUri` (inclusive Swagger) e scopes; `redirectUri`/`postLogoutRedirectUri` externalizáveis via `OAUTH_CLIENT_REDIRECT_URIS`/`OAUTH_CLIENT_POST_LOGOUT_URIS` (ver `docs/CONFIG.md`), sem reconciliação pós-seed.
  - Schemas SAS 7.0.3 adaptados (`blob`→`text`, `timestamp`→`timestamptz`, `IF NOT EXISTS`) em `src/main/resources/schema/`, aplicados via `spring.sql.init` (`continue-on-error: true`).
- **Chave JWT persistente** (`JWKConfig.java`):
  - Par RSA fixo com `kid` estável (`user-service-key`), carregado de PEM via `RsaKeyConverters` — em vez de gerar por boot.
  - Defaults de classpath (`src/main/resources/keys/app.{key,pub}`) são **dev** (gap conhecido); override em prod via `JWK_*`.
- **Sessão HTTP no Redis** via Spring Session:
  - `@EnableRedisHttpSession` no `SecurityConfig` — exige anotação explícita no Spring Boot 4.0.
  - Cookie renomeado para **`AUTHSESSION`** (`CookieSerializer`) para não colidir com o `SESSION` do gateway (ver [docs/CONVENCOES.md](docs/CONVENCOES.md)); flag `Secure` parametrizável via `app.cookie.secure` (`setUseSecureCookie`, default false p/ dev HTTP), simétrica ao gateway — o overlay TLS liga ambos via `APP_COOKIE_SECURE=true`.
- **Credenciais e token:** busca credenciais via Feign (`GET /internal/users/email/{email}`); customiza o JWT em `TokenCustomizerConfig.java` (**arquivo crítico**) com `userID`, `roles`, `permissions`.
- **Resiliência Feign:** `IUserClient` tem `fallbackFactory = UserClientFallbackFactory.class`. Circuit breaker Resilience4j (`spring.cloud.openfeign.circuitbreaker.enabled=true`, group por nome do client) com a config nomeada `configs.user-service` (com group habilitado, `instances.*` é inerte — use `configs.*`): janela 10, `minimumNumberOfCalls` 10, threshold 50%, open 10s, timeout 3s. Indisponibilidade do user-service retorna `UsernameNotFoundException` imediatamente em vez de travar em timeout. `AuthorizationService.loadUserByUsername` propaga essa exceção sem reembrulhar — o `DaoAuthenticationProvider` a trata como credenciais inválidas e devolve o usuário ao form de login (não escala a 500); só exceções inesperadas são convertidas em `UsernameNotFoundException` genérica sem vazar a causa.
- **Propagação de trace no Feign (`FeignTracingConfig.java`):** na cadeia Feign + circuit breaker a instrumentação automática (feign-micrometer) registrava o span cliente no trace correto, mas **não emitia os headers B3** — o user-service recebia a request sem `X-B3-*` e abria um trace **órfão**. `FeignTracingConfig` adiciona um `RequestInterceptor` que injeta o contexto de trace corrente no template via o `Propagator` do Micrometer (formato `b3`); assim o user-service (`consume: b3`) continua o trace e seu span vira filho do auth-server. Degrada graciosamente quando não há contexto ativo.
- **Lockout anti-brute-force:** `LoginAttemptService` mantém contador de falhas no Redis por par **(conta, IP)** — chave `sha256(emailLower|ip)`, janela fixa (TTL 15 min na 1ª falha), lockout após 5 falhas (`security.lockout.*`). `LoginAttemptListener` conta só `AuthenticationFailureBadCredentialsEvent` de form login; `AuthorizationService` devolve `accountNonLocked=false` quando bloqueado → `LockedException` antes da checagem de senha (mensagem genérica). **IP não-falsificável (ADR-010):** a fonte de IP é o header confiável `security.trusted-client-ip-header` (default `CF-Connecting-IP`, que a Cloudflare sobrescreve), com fallback em `getRemoteAddr()` sob `server.forward-headers-strategy=framework` (agora na **base** do config-server, não só nos overlays); o `X-Forwarded-For` bruto não é mais lido (`cloudflared` faz append → leftmost spoofável). `ClientIpResolver.currentIp(header)` continua a fonte ÚNICA do lockout.

### user-service (8090)

- **Domínio central:** CRUD de usuários. MongoDB (coleção `users`). Cache Redis: `usersById`, `usersByEmail`, `authByEmail`.
- **Controllers:**
  - `UserController` — público, via gateway.
  - `InternalUserController` — `GET /internal/users/email/{email}`, **não exposto pelo gateway**, só para o auth-server via Feign. Protegido por `X-Internal-Token` (`InternalTokenFilter`); acesso sem o header → 403.

### gateway (8081)

- **Base:** Spring Cloud Gateway (WebFlux/reativo, `spring-cloud-starter-gateway-server-webflux`). Único ponto de entrada externo — **nunca chame os serviços diretamente em produção**.
- **Cliente OAuth2 do BFF:** `oauth2Login` + `oauth2Client` (`gateway-client` confidencial) + resource server JWT. Guarda o token na sessão e o relaya downstream — o SPA nunca vê o JWT.
- **Rate limiting** via Redis (token bucket): LOW 2 req/s cap 5 (registro/IP), MED 5 req/s cap 10 (OAuth2/IP), HIGH 10 req/s cap 20 (autenticados/user). **IP não-falsificável (ADR-010):** `RateLimiterConfig`/`RateLimitLogFilter` resolvem o IP via `com.users.gateway.util.ClientIpResolver` — preferindo o header confiável `security.trusted-client-ip-header` (default `CF-Connecting-IP`), com fallback em `remoteAddress.getHostString()` (que sob `server.forward-headers-strategy=framework`, agora na base, reflete o XFF sanitizado pela borda; o `remoteAddress` vem _unresolved_, por isso `getHostString()` e não `getAddress()`). O `X-Forwarded-For` bruto não é mais lido — sob `cloudflared` (append) o leftmost é controlado pelo cliente.
- **Rotas em Java** (`GatewayRouter`, `RouteLocatorBuilder`). **`TokenRelay` é por rota** (na rota `user-service`), **não** via `default-filters` do yaml — a DSL Java não recebe default-filters.
- **CSRF** habilitado (`CookieServerCsrfTokenRepository`, cookie `XSRF-TOKEN`; `/v1/users/register` isento); o entry point devolve **401** (não 302); **logout RP-initiated**.
- **Sessão WebFlux no Redis** via Spring Session (`@EnableRedisWebSession` — exige anotação explícita). Guarda `OAuth2AuthorizedClient` (com JWT) + `SecurityContext`. Cookie `SESSION`.
- **Tracing no edge reativo:** sendo WebFlux, o `traceId`/`spanId` no `logging.pattern.level` (MDC) só é populado com `spring.reactor.context-propagation: auto` (no `gateway.yml`) — sem isso o log do gateway sai com `traceId=` vazio e a correlação log↔Zipkin quebra na borda.
- **Outros:** filtros `CorrelationIdFilter` (semeia o `X-Correlation-ID` a partir do traceId B3 corrente, com fallback UUID — id único de correlação alinhado ao trace) e `RateLimitLogFilter`; load balancing via Eureka (`lb://`).

### login-interface (5173 dev / 80 Docker)

- **Stack:** React 19 + TypeScript + Vite + TailwindCSS 4.
- **BFF funcionando ponta a ponta** — registro/login/perfil/logout. Token nunca toca o browser; zero `localStorage`.
- **Por que BFF (e não SPA-com-PKCE):**
  - **Token nunca toca o browser** (fica na sessão do gateway; cookie `HttpOnly`+`Secure`+`SameSite`) → XSS não exfiltra JWT/refresh, eliminando de raiz o gap "JWT em `localStorage`".
  - É a **recomendação do IETF** (BCP OAuth para apps com backend).
  - **Reaproveita o backend** existente.
  - **Menos peça móvel no front:** sem PKCE, sem `/callback`, sem refresh manual.
  - **Alinha com a sessão no Redis.**
- **Mecânica:**
  - **Same-origin via proxy** (Vite em dev, nginx no Docker): `/users`, `/oauth2`, `/login/oauth2`, `/logout` → gateway (só `/login/oauth2`, não `/login` puro).
  - **Sem token no front:** `apiAxios` com `withCredentials`, sem `Authorization: Bearer`; estado de auth derivado de `GET /v1/users/me` (200 vs 401).
  - **Hostname OAuth em Docker:** `OAUTH_AUTHORIZATION_URI` aponta para `localhost:8082` (front-channel/browser), enquanto `issuer-uri`/token/jwks ficam no hostname interno `authorization-server:8082`.
  - **CSRF** via `X-XSRF-TOKEN`.
  - **Logout:** form oculto `POST /logout` → `end_session_endpoint`.

---

## Convenções e Invariantes

Detalhe e racional em [docs/CONVENCOES.md](docs/CONVENCOES.md); decisões formais em [docs/adr/](docs/adr/). As invariantes a **preservar** (quebrá-las reintroduz bugs já resolvidos):

- **Separação rígida:** o authorization-server não acessa MongoDB — só via Feign para o user-service.
- **Canal interno isolado:** `/internal/users/email/{email}` fora do gateway e do Swagger, protegido por `X-Internal-Token` (ADR-006).
- **DELETE com 3 semânticas intencionais:** soft-delete ADMIN (`/{id}`), hard-delete ADMIN (`/del/{id}`), soft-delete USER (`/remove/me`) (ADR-001).
- **BCrypt** custo 10; **roles fixas** `USER`/`ADMIN` (sem roles dinâmicas).
- **Cookies de sessão distintos:** gateway `SESSION`, auth-server `AUTHSESSION` — evita colisão no salto front-channel; cada serviço também usa `redisNamespace` próprio (`gateway:session` / `authserver:session`) isolando as sessões no Redis (ADR-007).
- **Spring Session explícito no Boot 4.0:** `@EnableRedisWebSession` (gateway) / `@EnableRedisHttpSession` (auth-server) — a autoconfig não dispara só pela dependência.
- **Config mutável em containers:** Redis Sentinel e MongoDB keyfile usam o padrão copy-to-`/tmp` + override de `entrypoint` (Mongo); não troque por mount `:ro` simples.
- **Autenticação no Redis (ADR-008):** senha única `REDIS_PASSWORD` (fail-fast, sem default no compose) nos 6 nós. `requirepass` **e** `masterauth` nos **três** data nodes — sem `masterauth` no master atual, o ex-master não reintegra como réplica pós-failover (PSYNC NOAUTH). Sentinels com `requirepass` + `sentinel auth-pass mymaster` (injetados em runtime, fora do `sentinel.conf` versionado). Clientes Spring precisam das **duas** propriedades: `spring.data.redis.password` (data nodes) **e** `spring.data.redis.sentinel.password` (sentinels) — omitir a segunda causa NOAUTH lazy só em runtime (o CI, com Redis standalone sem senha, não pega).

---

## Qualidade e Verificação

A garantia de que o sistema continua saudável vem de testes + gate de cobertura + CI. Estratégia completa em [docs/TESTES.md](docs/TESTES.md) e [docs/LOGS.md](docs/LOGS.md).

- **Testes (271):** 113 unitários (Mockito/reativos) + 51 controller (`@WebMvcTest` — 46 `UserControllerTest` + 5 `InternalUserControllerTest`) + 68 integração (user-service: Mongo+Redis; auth-server: Postgres+Redis+WireMock, fluxo OAuth2; gateway: Redis+WireMock via `WebTestClient`, roteamento/rate-limit/CSRF + BFF ponta a ponta; config-server: `MockMvc` p/ HTTP Basic) + 39 front-end (Vitest+RTL+MSW, threshold 80%).
- **Gate de cobertura JaCoCo:** roda na fase `verify` (regra `check`, piso **70%** LINE/BUNDLE nos 3 módulos de domínio — user-service/auth-server/gateway; config-server/discovery-server são report-only). Classes novas/alteradas: alvo **80%**. Ver [docs/TESTES.md § Cobertura (JaCoCo)](docs/TESTES.md).
- **CI** (`.github/workflows/ci.yml`): a cada push/PR roda `mvn verify` por módulo (matrix `backend`, que dispara o gate) + `npm run coverage` no front (job `frontend`) + validação da topologia base (`compose-validate`). A `main` exige todos os checks verdes para merge (branch protection) — detalhes na seção _Integração Contínua (CI)_ do [README.md](README.md).
- **Logs:** SLF4J parametrizado (`{}`), formato em pipe (`| [VERBO] | ação | campo: valor`), níveis INFO/WARN/ERROR/DEBUG convencionados, `traceId`/`spanId` via B3, PII mascarada (`LogUtils.maskEmail()`).

---

## Gaps de Segurança Conhecidos

Controles ativos e dívida aceita detalhados em [docs/SECURITY.md](docs/SECURITY.md). Gaps **ativos** (dívida consciente, não regredir os controles existentes): sem TLS em prod (curativos `docker-compose.tls.yml` em dev e `docker-compose.deploy.yml`/Cloudflare quick tunnel — este último valida a mecânica mas a URL efêmera **não** cruza a barra; exige named tunnel + domínio), segredos em **Docker secrets** mas ainda arquivos no host (gap 0.3 fechado **parcialmente** — sem secret manager/rotação) com resíduo do `mongodb-exporter` (distroless) lendo `MONGO_*` do `.env`, keyfile MongoDB de dev no repo, TLS de transporte Redis ausente (senha trafega em claro na rede interna Docker; portas Redis/Sentinel nunca publicadas no compose base), ACLs Redis por usuário ausentes (todos os clientes compartilham `REDIS_PASSWORD`). **Fechado:** chave JWK fora do repositório (gap 0.1 — gerada por `infra/jwk/gen-keys.sh`, secret no compose; [ADR-005](docs/adr/ADR-005-chave-jwk-persistente.md)) e Grafana via secret (`__FILE`). Ao fechar um gap ou introduzir dívida, atualize `docs/SECURITY.md` e `.claude/memory/decisions.md`.

---

## Orquestração de Agentes

Mudanças de domínio (feature/bugfix/hotfix/novo serviço/atualização de dependências) passam por
um time de **7 subagentes** (`.claude/agents/`) conduzido pelo thread principal — protocolo,
papéis e regras invioláveis em [docs/ORQUESTRACAO.md](docs/ORQUESTRACAO.md). Resumo do pipeline:
`product-manager → senso-critico → techlead → qa-tester → [security-reviewer] → senso-critico →
doc-keeper` (o `security-reviewer` é condicional à superfície de segurança; o `dependency-steward`
conduz o workflow `dependency-update`). Estado persistente em `.claude/memory/` (`context.json`,
`decisions.md`, `blockers.md`); workflows em `.claude/workflows/`. **Regras-chave:** nunca pule o
`senso-critico` em mudança de contrato de API, nem o `security-reviewer` quando a segurança é
tocada; mudança de contrato/schema **exige ADR**; após 2 rodadas de revisão sem aprovação, escale
ao humano. Skills invocáveis: `/suggest-tests`, `/check-compat`, `/security-scan`, `/new-adr`. Os
agentes também podem ser chamados isoladamente.
