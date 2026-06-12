# CLAUDE.md

## Restrições de Execução

- Não refatore código fora do escopo explícito da tarefa pedida
- Não adicione tratamento de erro para cenários impossíveis
- Não crie arquivos sem ser pedido explicitamente
- Pergunte antes de agir se a tarefa tiver mais de 3 arquivos envolvidos
- Antes de cada alteração no código, apresente um relatório que aponte claramente: 1) razões dos novos códigos e/ou das modificações; 2) arquivos a serem criados, se houver; 3) arquivos a serem modificados, se houver

---

## Visão Geral do Projeto

Sistema de microsserviços em Java + Spring para gerenciamento de usuários, pronto para produção. O objetivo central é fornecer uma base sólida de autenticação, registro e controle de acesso, sobre a qual outras camadas de domínio serão adicionadas futuramente, ou seja, um blueprint de um sistema de usuários.

O front-end React (`login-interface`) usa o padrão **BFF**: o gateway é o cliente OAuth2, o SPA usa sessão por cookie e **não** manuseia JWT.

**Mapa de documentos:**

- [README.md](README.md) — pré-requisitos e execução (humano)
- [docs/SERVICOS.md](docs/SERVICOS.md) — referência da API (endpoints, schema MongoDB, cache)
- [docs/CONFIG.md](docs/CONFIG.md) — variáveis de ambiente
- [docs/TESTES.md](docs/TESTES.md) — estratégia de testes
- [docs/LOGS.md](docs/LOGS.md) — estratégia de logs
- [docs/GAPS_SEGURANCA.md](docs/GAPS_SEGURANCA.md) — gaps de segurança conhecidos
- [docs/TRABALHO_PENDENTE.md](docs/TRABALHO_PENDENTE.md) — roadmap e correções (C7–C19)
- [docs/AVALIACAO.md](docs/AVALIACAO.md) — avaliação técnica do projeto por nível
- [docs/CHECKLIST.md](docs/CHECKLIST.md) — checklist de features obrigatórias de um blueprint de sistema de usuários + tabela de números-chave

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

---

## Serviços

> Referência da API (endpoints, schema, cache) em [docs/SERVICOS.md](docs/SERVICOS.md). Aqui ficam só as responsabilidades não-óbvias e os arquivos críticos.

### config-server (8888)

- **Config centralizada** via `classpath:/config`; os demais serviços importam com `spring.config.import=optional:configserver:${CONFIG_SERVER_URL}`.
- Arquivos em `config-server/.../config/{servico}.yml`.
- **HA:** duas instâncias (`config-server-1`, `config-server-2`) atrás de `config-lb` (nginx). `CONFIG_SERVER_URL` aponta para `config-lb:8888`. Stateless — qualquer instância serve a mesma config.
- **Deve subir primeiro** — todos os demais dependem dele via `config-lb`.
- **HTTP Basic (C17):** `SecurityConfig` exige autenticação no endpoint (`/actuator/health` aberto p/ healthchecks; CSRF off — cliente é máquina). Os clientes enviam `spring.cloud.config.username/password`; par único `CONFIG_SERVER_USERNAME`/`CONFIG_SERVER_PASSWORD` (default dev no `application.yml`, sem default no compose → fail-fast).

### discovery-server (9091 / 9092)

- **Netflix Eureka HA** — dois nós em peer replication (`discovery-server-1:9091`, `discovery-server-2:9092`); cada instância se registra na outra via `EUREKA_PEER_URL`.
- `EUREKA_URI` nos demais serviços lista ambas as instâncias (CSV).
- Sobe após `config-lb`.

### authorization-server (8082)

- **Tipo:** OAuth2 Authorization Server (Spring Security). Fluxo authorization_code + PKCE + refresh_token; OIDC (`openid`, `profile`).
- **Estado OAuth em PostgreSQL** (escala horizontal):
  - `JdbcRegisteredClientRepository` + `JdbcOAuth2AuthorizationService` + `JdbcOAuth2AuthorizationConsentService` (`OAuth2ClientConfig.java`).
  - `gateway-client` **semeado idempotentemente** na subida (`findByClientId` → `save`), preservando `redirectUri` (inclusive Swagger) e scopes.
  - Schemas SAS 7.0.3 adaptados (`blob`→`text`, `timestamp`→`timestamptz`, `IF NOT EXISTS`) em `src/main/resources/schema/`, aplicados via `spring.sql.init` (`continue-on-error: true`).
- **Chave JWT persistente** (`JWKConfig.java`):
  - Par RSA fixo com `kid` estável (`user-service-key`), carregado de PEM via `RsaKeyConverters` — em vez de gerar por boot.
  - Defaults de classpath (`src/main/resources/keys/app.{key,pub}`) são **dev** (gap conhecido); override em prod via `JWK_*`.
- **Sessão HTTP no Redis** via Spring Session:
  - `@EnableRedisHttpSession` no `SecurityConfig` — exige anotação explícita no Spring Boot 4.0.
  - Cookie renomeado para **`AUTHSESSION`** (`CookieSerializer`) para não colidir com o `SESSION` do gateway (ver _Convenções_).
- **Credenciais e token:** busca credenciais via Feign (`GET /internal/users/email/{email}`); customiza o JWT em `TokenCustomizerConfig.java` (**arquivo crítico**) com `userID`, `roles`, `permissions`.
- **Resiliência Feign (C7):** `IUserClient` tem `fallbackFactory = UserClientFallbackFactory.class`. Circuit breaker Resilience4j (`spring.cloud.openfeign.circuitbreaker.enabled=true`, group por nome do client) com instância `user-service`: janela 10, threshold 50%, open 10s, timeout 3s. Indisponibilidade do user-service retorna `UsernameNotFoundException` imediatamente em vez de travar em timeout.
- **Lockout anti-brute-force (C19):** `LoginAttemptService` mantém contador de falhas no Redis por par **(conta, IP)** — chave `sha256(emailLower|ip)`, janela fixa (TTL 15 min na 1ª falha), lockout após 5 falhas (`security.lockout.*`). `LoginAttemptListener` conta só `AuthenticationFailureBadCredentialsEvent` de form login; `AuthorizationService` devolve `accountNonLocked=false` quando bloqueado → `LockedException` antes da checagem de senha (mensagem genérica). Prod exige `server.forward-headers-strategy` + proxy sanitizando `X-Forwarded-For`.

### user-service (8090)

- **Domínio central:** CRUD de usuários. MongoDB (coleção `users`). Cache Redis: `usersById`, `usersByEmail`, `authByEmail`.
- **Controllers:**
  - `UserController` — público, via gateway.
  - `InternalUserController` — `GET /internal/users/email/{email}`, **não exposto pelo gateway**, só para o auth-server via Feign. Protegido por `X-Internal-Token` (`InternalTokenFilter`); acesso sem o header → 403.

### gateway (8081)

- **Base:** Spring Cloud Gateway (WebFlux/reativo, `spring-cloud-starter-gateway-server-webflux`). Único ponto de entrada externo — **nunca chame os serviços diretamente em produção**.
- **Cliente OAuth2 do BFF:** `oauth2Login` + `oauth2Client` (`gateway-client` confidencial) + resource server JWT. Guarda o token na sessão e o relaya downstream — o SPA nunca vê o JWT.
- **Rate limiting** via Redis (token bucket): LOW 2 req/s cap 5 (registro/IP), MED 5 req/s cap 10 (OAuth2/IP), HIGH 10 req/s cap 20 (autenticados/user). O IP é lido com `getHostString()` (`RateLimiterConfig`/`RateLimitLogFilter`): com `server.forward-headers-strategy=framework` (borda TLS) o WebFlux consome o `X-Forwarded-For` e o `remoteAddress` vira `InetSocketAddress` *unresolved* — `getAddress()` é null e `getHostAddress()` daria NPE.
- **Rotas em Java** (`GatewayRouter`, `RouteLocatorBuilder`). **`TokenRelay` é por rota** (na rota `user-service`), **não** via `default-filters` do yaml — a DSL Java não recebe default-filters.
- **CSRF** habilitado (`CookieServerCsrfTokenRepository`, cookie `XSRF-TOKEN`; `/v1/users/register` isento); o entry point devolve **401** (não 302); **logout RP-initiated**.
- **Sessão WebFlux no Redis** via Spring Session (`@EnableRedisWebSession` — exige anotação explícita). Guarda `OAuth2AuthorizedClient` (com JWT) + `SecurityContext`. Cookie `SESSION`.
- **Outros:** filtros `CorrelationIdFilter` e `RateLimitLogFilter`; load balancing via Eureka (`lb://`).

### login-interface (5173 dev / 80 Docker)

- **Stack:** React 19 + TypeScript + Vite + TailwindCSS 4.
- **Estado: BFF implementado e confirmado** — registro/login/perfil/logout funcionando ponta a ponta. Token nunca toca o browser; zero `localStorage`.
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

## Fluxo de Autenticação (OAuth2 / BFF)

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

## Convenções e Decisões de Design

- **Separação de responsabilidades rígida:** o authorization-server não acessa MongoDB — apenas via Feign para o user-service.
- **Endpoint interno isolado:** `/internal/users/email/{email}` não está no gateway nem no Swagger — canal exclusivo auth-server ↔ user-service.
  - Protegido por shared secret `X-Internal-Token` (`InternalTokenFilter` valida; `FeignConfig` injeta); acesso direto à 8090 sem o header → 403.
- **DELETE com semânticas distintas e intencionais:**
  - `DELETE /v1/users/{id}` (ADMIN) → soft-delete (`deactivateUser`, `active=false`).
  - `DELETE /v1/users/del/{id}` (ADMIN) → hard-delete (`deleteUser`).
  - `DELETE /v1/users/remove/me` (USER) → soft-delete.
- **BCrypt** custo padrão (10).
- **Roles fixas:** apenas `USER` e `ADMIN` (strings simples no MongoDB) — sem roles dinâmicas.
- **Configuração centralizada:** segredos vêm do config-server via env. Segredos hardcoded são gaps conhecidos, não padrão.
- **Cookies de sessão distintos por serviço:** gateway `SESSION`, auth-server `AUTHSESSION` (`CookieSerializer`).
  - Em dev/Docker ambos compartilham `localhost` e os cookies **ignoram a porta**; com o mesmo nome, o cookie do auth-server sobrescreveria o do gateway no salto front-channel → o callback lê a sessão errada → `authorization_request_not_found`.
  - Nomes distintos evitam a colisão (em prod, domínios separados também resolveriam).
- **Spring Session exige habilitação explícita no Spring Boot 4.0:** `@EnableRedisWebSession` (gateway, reativo) e `@EnableRedisHttpSession` (auth-server, servlet) — a autoconfig não dispara só pela dependência.
- **Arquivos de configuração mutáveis em containers (Redis Sentinel e MongoDB):** ambos precisam gravar no arquivo de config em runtime, o que impede o mount simples com `:ro`.
  - **Redis Sentinel** regrava o arquivo para persistir estado (master atual, sentinel IDs). Fix: `command: sh -c "cp /etc/redis/sentinel.conf /tmp/sentinel.conf && exec redis-sentinel /tmp/sentinel.conf"` — copia o mount `:ro` para `/tmp` gravável antes de iniciar.
  - **MongoDB keyfile** precisa de `chmod 400` e `chown 999` antes que o mongod o leia. Fix: `entrypoint:` override (não `command:`) que copia para `/tmp/mongo.key`, ajusta permissões e chama `exec docker-entrypoint.sh mongod`. Usar `command:` em vez de `entrypoint:` contornaria o `docker-entrypoint.sh` original e `MONGO_INITDB_ROOT_USERNAME` nunca seria processado.

---

## Desenvolvimento Local

### Subir tudo com Docker

```bash
docker compose up -d --build
```

O `docker-compose.yml` é **base prod-safe** (publica só `gateway:8081` e `interface`); o `docker-compose.override.yml` (auto-carregado por `docker compose up`) republica as portas internas para **dev**. Para um deploy prod-like (só a borda exposta), rode `docker compose -f docker-compose.yml up` (ignora o override) com um `.env` setando as URLs públicas — ver `.env.example`.

**TLS/HTTPS na borda em dev (opcional, G1):** overlay opt-in `docker-compose.tls.yml` sobe um reverse-proxy nginx (`infra/tls-proxy`) que termina TLS com cert do **mkcert** e fala HTTPS com o browser (`app.localhost`/`auth.localhost`), mantendo o interno em HTTP. `docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build`. O Swagger-UI segue funcional nesse modo: o fluxo OAuth2 passa pela borda (`AUTH_URL`/`AUTH_TOKEN` → `https://auth.localhost`; CORS do auth-server inclui `http://localhost:8081`) — a porta 8082 não é publicada sem o override de dev. Setup e verificação em [docs/TLS_DEV.md](docs/TLS_DEV.md).

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

- **Zipkin** — B3, 100% sampling
- **Prometheus** — `/actuator/prometheus`, scrape 5s
- **Grafana** — dashboards pré-provisionados
- **SLOs** — 50ms / 100ms / 200ms / 500ms / 1s / 2s

---

## Estratégia de Testes

Ver [docs/TESTES.md](docs/TESTES.md). Resumo: 45 unitários (Mockito) + 49 controller (`@WebMvcTest` — 45 `UserControllerTest` + 4 `InternalUserControllerTest`) + 35 integração (Testcontainers — user-service: Mongo+Redis; auth-server: Postgres+Redis+WireMock, fluxo OAuth2); front-end sem cobertura hoje.

---

## Estratégia de Logs

Ver [docs/LOGS.md](docs/LOGS.md). Resumo: SLF4J parametrizado (`{}`), formato em pipe (`| [VERBO] | ação | campo: valor`), níveis INFO/WARN/ERROR/DEBUG convencionados, `traceId`/`spanId` via B3, PII mascarada (`LogUtils.maskEmail()`).

---

## Trabalho Pendente e Correções Necessárias

Ver [docs/TRABALHO_PENDENTE.md](docs/TRABALHO_PENDENTE.md) (roadmap por tema/prioridade + correções C7–C19).

---

## Gaps de Segurança Conhecidos

Ver [docs/GAPS_SEGURANCA.md](docs/GAPS_SEGURANCA.md). **Gaps ativos:** sem TLS/HTTPS em prod (alta, G1 — **curativo:** borda TLS de dev via nginx+mkcert no overlay `docker-compose.tls.yml`, ver [docs/TLS_DEV.md](docs/TLS_DEV.md); falta cert ACME/domínios reais em prod), chave JWK dev no classpath (média, G5 — aceito, override em prod via `JWK_*`), Grafana `admin/admin` (baixa, G11 — curativo, externalizado para `.env`), keyfile MongoDB de dev rastreado no repositório (média, G12 — aceito, análogo a G5), Redis/Sentinel sem autenticação (média, G13 — aberto, mitigado por portas nunca publicadas). **Resolvidos** (IDs preservados, não reusados): G2 (C16, compose prod-safe), G3 (C17, config-server Basic auth + porta fechada + sem defaults de secret), G4 (C11, secrets em `.env`), G6 (C18, management interna), G7 (C12, CORS configurável: gateway p/ SPA, auth-server p/ Swagger; user-service sem CORS), G8 (C8, `permissions` por roles), G9 (C13, validação de senha declarativa — 8–72 chars com letra e número), G10 (C19, lockout por conta+IP); JWT em `localStorage` via BFF.

---

## Agentes Disponíveis

Subagentes especializados em `.claude/agents/`. Use via Claude Code quando a tarefa se encaixar no escopo de cada um.

| Agente | Arquivo | Quando usar |
|--------|---------|-------------|
| `security-auditor` | `.claude/agents/security-auditor.md` | Antes de PRs de hardening; "qual gap (G1–G12) fechar agora?" |
| `doc-keeper` | `.claude/agents/doc-keeper.md` | Após commits que alteram endpoints, schema, testes ou itens do roadmap |
| `backlog-driver` | `.claude/agents/backlog-driver.md` | "Execute o próximo item do backlog" ou "implemente C\<n\>" |
| `error-analyst` | `.claude/agents/error-analyst.md` | Após sessão de mudanças grandes; testes quebrando; auditoria preventiva |

**Fluxo típico entre agentes:**

```
backlog-driver (implementa C7–C19)
       ├─► doc-keeper      (atualiza TRABALHO_PENDENTE.md e docs afetados)
       └─► error-analyst   (verifica se a mudança introduz nova falha)

security-auditor (fecha G1–G11)
       └─► error-analyst   (valida que o patch não quebra comportamento existente)

error-analyst (auditoria diagnóstica)
       ├─► backlog-driver  (se o erro tem correção mapeada em C7–C19)
       └─► security-auditor (se o erro tem implicação de segurança)
```
