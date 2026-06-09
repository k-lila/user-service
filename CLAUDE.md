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

- [Readme.md](Readme.md) — pré-requisitos e execução (humano)
- [docs/SERVICOS.md](docs/SERVICOS.md) — referência da API (endpoints, schema MongoDB, cache)
- [docs/CONFIG.md](docs/CONFIG.md) — variáveis de ambiente
- [docs/TESTES.md](docs/TESTES.md) — estratégia de testes
- [docs/LOGS.md](docs/LOGS.md) — estratégia de logs
- [docs/GAPS_SEGURANCA.md](docs/GAPS_SEGURANCA.md) — gaps de segurança conhecidos
- [docs/TRABALHO_PENDENTE.md](docs/TRABALHO_PENDENTE.md) — roadmap e correções (C7–C19)
- [docs/AVALIACAO.md](docs/AVALIACAO.md) — avaliação técnica do projeto por nível

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
        │       ├── chama user-service via Feign (endpoint interno)
        │       ├── PostgreSQL (auth-postgres :5432 — estado OAuth: client/authorizations/consents)
        │       └── Redis (sessão de login/consent)
        │
        ├── user-service :8090
        │       ├── MongoDB (persistência)
        │       └── Redis (cache + rate limiting)
        │
        ├── discovery-server :9091  (Eureka)
        ├── config-server :8888     (Spring Cloud Config)
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
- **Deve subir primeiro** — todos os demais dependem dele.

### discovery-server (9091)

- **Netflix Eureka** — registra e descobre serviços; ele próprio **não** se registra.
- Sobe logo após o config-server.

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

### user-service (8090)

- **Domínio central:** CRUD de usuários. MongoDB (coleção `users`). Cache Redis: `usersById`, `usersByEmail`, `authByEmail`.
- **Controllers:**
  - `UserController` — público, via gateway.
  - `InternalUserController` — `GET /internal/users/email/{email}`, **não exposto pelo gateway**, só para o auth-server via Feign. Protegido por `X-Internal-Token` (`InternalTokenFilter`); acesso sem o header → 403.

### gateway (8081)

- **Base:** Spring Cloud Gateway (WebFlux/reativo, `spring-cloud-starter-gateway-server-webflux`). Único ponto de entrada externo — **nunca chame os serviços diretamente em produção**.
- **Cliente OAuth2 do BFF:** `oauth2Login` + `oauth2Client` (`gateway-client` confidencial) + resource server JWT. Guarda o token na sessão e o relaya downstream — o SPA nunca vê o JWT.
- **Rate limiting** via Redis (token bucket): LOW 2 req/s cap 5 (registro/IP), MED 5 req/s cap 10 (OAuth2/IP), HIGH 10 req/s cap 20 (autenticados/user).
- **Rotas em Java** (`GatewayRouter`, `RouteLocatorBuilder`). **`TokenRelay` é por rota** (na rota `user-service`), **não** via `default-filters` do yaml — a DSL Java não recebe default-filters.
- **CSRF** habilitado (`CookieServerCsrfTokenRepository`, cookie `XSRF-TOKEN`; `/users/register` isento); o entry point devolve **401** (não 302); **logout RP-initiated**.
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
  - **Sem token no front:** `apiAxios` com `withCredentials`, sem `Authorization: Bearer`; estado de auth derivado de `GET /users/me` (200 vs 401).
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
  - `DELETE /users/{id}` (ADMIN) → soft-delete (`deactivateUser`, `active=false`).
  - `DELETE /users/del/{id}` (ADMIN) → hard-delete (`deleteUser`).
  - `DELETE /users/remove/me` (USER) → soft-delete.
- **BCrypt** custo padrão (10).
- **Roles fixas:** apenas `USER` e `ADMIN` (strings simples no MongoDB) — sem roles dinâmicas.
- **Configuração centralizada:** segredos vêm do config-server via env. Segredos hardcoded são gaps conhecidos, não padrão.
- **Cookies de sessão distintos por serviço:** gateway `SESSION`, auth-server `AUTHSESSION` (`CookieSerializer`).
  - Em dev/Docker ambos compartilham `localhost` e os cookies **ignoram a porta**; com o mesmo nome, o cookie do auth-server sobrescreveria o do gateway no salto front-channel → o callback lê a sessão errada → `authorization_request_not_found`.
  - Nomes distintos evitam a colisão (em prod, domínios separados também resolveriam).
- **Spring Session exige habilitação explícita no Spring Boot 4.0:** `@EnableRedisWebSession` (gateway, reativo) e `@EnableRedisHttpSession` (auth-server, servlet) — a autoconfig não dispara só pela dependência.

---

## Desenvolvimento Local

### Subir tudo com Docker

```bash
docker compose up -d --build
```

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

Ver [docs/TESTES.md](docs/TESTES.md). Resumo: 32 unitários (Mockito) + 46 controller (`@WebMvcTest` — 41 `UserControllerTest` + 5 `InternalUserControllerTest`) + 32 integração (Testcontainers Mongo+Redis); front-end sem cobertura hoje.

---

## Estratégia de Logs

Ver [docs/LOGS.md](docs/LOGS.md). Resumo: SLF4J parametrizado (`{}`), formato em pipe (`| [VERBO] | ação | campo: valor`), níveis INFO/WARN/ERROR/DEBUG convencionados, `traceId`/`spanId` via B3, PII mascarada (`LogUtils.maskEmail()`).

---

## Trabalho Pendente e Correções Necessárias

Ver [docs/TRABALHO_PENDENTE.md](docs/TRABALHO_PENDENTE.md) (roadmap por tema/prioridade + correções C7–C19).

---

## Gaps de Segurança Conhecidos

Ver [docs/GAPS_SEGURANCA.md](docs/GAPS_SEGURANCA.md). 11 gaps mapeados (G1–G11): sem TLS/HTTPS (alta, G1), portas internas publicadas (alta, G2), config-server sem auth (média, G3), secrets em claro no compose (média, G4), chave JWK dev no classpath (média, G5 — aceito, override em prod via `JWK_*`), actuator sem auth na borda (média, G6), CORS duplicado e hardcoded (média, G7 — curativo aplicado), `permissions` hardcoded (média, G8), validação de senha fraca (baixa, G9), sem brute-force/lockout (média, G10), Grafana `admin/admin` (baixa, G11). JWT em `localStorage` resolvido via BFF.

---

## Agentes Disponíveis

Subagentes especializados em `.claude/agents/`. Use via Claude Code quando a tarefa se encaixar no escopo de cada um.

| Agente | Arquivo | Quando usar |
|--------|---------|-------------|
| `security-auditor` | `.claude/agents/security-auditor.md` | Antes de PRs de hardening; "qual gap (G1–G11) fechar agora?" |
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
