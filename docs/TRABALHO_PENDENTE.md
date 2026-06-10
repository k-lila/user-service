# Trabalho Pendente

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Para a visão geral do projeto, arquitetura e convenções, ver [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler](#como-ler)
- [Roadmap sugerido](#roadmap-sugerido)
- [1. Resiliência e escalabilidade](#1-resiliência-e-escalabilidade)
- [2. Hardening de segurança](#2-hardening-de-segurança)
- [3. Qualidade e API](#3-qualidade-e-api)
- [4. Eficiência e operação](#4-eficiência-e-operação)
- [5. Evolução de domínio (futuro)](#5-evolução-de-domínio-futuro)

## Como ler

- **Contexto:** o sistema roda em **múltiplas instâncias** (escala horizontal) e serve de **base/template reutilizável** pronta para produção.
- **ID `C#`:** âncora estável de cada correção (referenciada por outros docs). Não é reordenável nem reusável.
- **Prioridade:** Alta / Média / Baixa (impacto no objetivo de base sólida e multi-instância).
- **Esforço:** P / M / G.
- **Ref:** gap correlato em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md) — lá fica o **risco/severidade**; aqui, o **plano acionável**.

## Roadmap sugerido

Ordem sugerida: **1 → 2 → 3 → 4 → 5** (a evolução de domínio vem depois da base estável).

| ID  | Item                                             | Prioridade | Esforço | Ref      |
| --- | ------------------------------------------------ | ---------- | ------- | -------- |
| C7  | Circuit breaker (Resilience4j) na chamada Feign  | Alta       | M       | —        |
| —   | Deploy rolling (graceful shutdown + probes)      | Média      | M       | —        |
| —   | Eliminar SPOFs (infra HA)                        | Média      | G       | —        |
| C16 | Não publicar portas internas em produção         | Alta       | M       | G2       |
| C11 | Secrets fora do `docker-compose.yml`             | Média      | P       | G4, G11  |
| C12 | CORS na borda + configurável                     | Média      | M       | G7       |
| C17 | Proteger/segregar o config-server                | Média      | M       | G3       |
| C18 | Restringir actuator na borda pública             | Média      | P       | G6       |
| C19 | Lockout / anti-brute-force no login              | Média      | M       | G10      |
| C8  | `permissions` derivadas das roles                | Média      | P       | G8       |
| —   | TLS/HTTPS                                         | Alta       | M       | G1       |
| C9  | Erros padronizados (RFC 7807 / `ProblemDetail`)  | Média      | M       | —        |
| C10 | Cobertura de testes (gateway/auth/front)         | Média      | M       | —        |
| C13 | Validação de senha unificada e forte             | Baixa      | P       | G9       |
| —   | Versionamento de API (`/v1`)                     | Baixa      | M       | —        |
| C14 | Eliminar a dupla chamada Feign por login         | Baixa      | M       | —        |
| —   | Pipeline de CI                                   | Média      | M       | —        |
| C15 | Higiene cosmética                                | Baixa      | P       | —        |

> Já adequados para escala (não há trabalho pendente): chave JWK persistente (`kid` fixo), estado OAuth em Postgres (JDBC), sessão no Redis (Spring Session), validação JWT stateless, rate limiter e caches no Redis, healthchecks de container + `depends_on` no `docker-compose.yml`, Eureka HA (peer replication), config-server HA (nginx LB), MongoDB replica set (`rs0`), Redis Sentinel, graceful shutdown (`server.shutdown=graceful`) + readiness/liveness probes, circuit breaker Resilience4j (auth-server → user-service via `UserClientFallbackFactory`).

## 1. Resiliência e escalabilidade

- [x] **C7 — Circuit breaker na chamada Feign** (auth-server → user-service). Hoje a indisponibilidade do user-service derruba o login (`IUserClient` sem fallback). · **Prioridade: Alta · Esforço: M**
  - `authorization-server/pom.xml` — adicionar `spring-cloud-starter-circuitbreaker-resilience4j`.
  - Criar `clients/UserClientFallbackFactory.java` — `FallbackFactory<IUserClient>` que lança `UsernameNotFoundException` ao acionar o fallback.
  - `clients/IUserClient.java` — adicionar `fallbackFactory = UserClientFallbackFactory.class` no `@FeignClient`.
  - `config-server/.../authorization-server.yml` — `spring.cloud.openfeign.circuitbreaker.enabled=true` + bloco `resilience4j.circuitbreaker.instances.user-service` (`slidingWindowSize`, `failureRateThreshold`, `waitDurationInOpenState`, `permittedNumberOfCallsInHalfOpenState`).

- [x] **Deploy rolling sem downtime** — `server.shutdown=graceful` + readiness/liveness probes (actuator) em todos os serviços. _(O compose já tem healthchecks de container + `depends_on: service_healthy`; falta o nível de aplicação.)_ · **Prioridade: Média · Esforço: M**

- [x] **Eliminar SPOFs (infra)** — `discovery-server` em peer replication (Eureka HA), `config-server` replicado, MongoDB replica set e Redis Sentinel/Cluster. · **Prioridade: Média · Esforço: G**

## 2. Hardening de segurança

> Risco e severidade de cada item em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md). Aqui fica o plano de correção.

- [x] **C16 — Não publicar portas internas em produção** (ref **G2**). `docker-compose.yml` virou **base prod-safe**: publica só `gateway` (8081) e `interface` (`${WEB_HOST_PORT:-5173}:80`); user-service, config-lb, discovery ×2, auth-server, zipkin, prometheus e grafana ficam **sem `ports:`** (só na rede interna). `docker-compose.override.yml` (auto-carregado) republica essas portas em **dev**. As URLs voltadas ao browser viraram `${VAR:-localhost-default}` no compose (prod sobrescreve via `.env`). **Comandos:** dev `docker compose up`; prod `docker compose -f docker-compose.yml up`. **Pendência:** o front-channel do auth-server (`/oauth2/authorize`, `/login`, `/connect/logout`) é exposto pelo reverse-proxy de TLS/ingress num hostname dedicado — delegado ao item **TLS/HTTPS (G1)**, não ao compose. · **Prioridade: Alta · Esforço: M**

- [x] **C11 — Secrets fora do `docker-compose.yml`** (ref **G4**, **G11**). Mongo (`MONGO_USER`/`MONGO_PASSWORD`), Postgres (`POSTGRES_USER`/`POSTGRES_PASSWORD`), `OAUTH_CLIENT_SECRET` (incl. o que estava hardcoded no gateway), `INTERNAL_API_TOKEN` e Grafana movidos para `.env` git-ignored, referenciados por `${VAR}` **sem default** (falta de `.env` derruba a subida). Template versionado em `.env.example` (exceção `!.env.example` no `.gitignore`). · **Prioridade: Média · Esforço: P**

- [x] **C12 — CORS na borda + configurável** (ref **G7**). CORS agora só na borda (gateway), configurável por ambiente. **Implementado:** · **Prioridade: Média · Esforço: M**
  - **C — CORS removido do user-service:** apagado o bean `CorsFilter` (`config/CORSConfig.java`) e o `.cors(...)` do `SecurityConfig` (reverte o curativo da Opção A). O user-service nunca é chamado direto pelo browser (só via gateway) → guard é JWT + isolamento de rede.
  - **D — origens do gateway externalizadas:** `gateway/.../config/CORSConfig.java` lê `@Value("${cors.allowed-origins:http://localhost:5173}")` (CSV → `List<String>`), usa `setAllowedOriginPatterns(...)` (compatível com `allowCredentials(true)`) e loga a allowlist efetiva no startup; `gateway.yml` → `cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}`; `docker-compose.yml` passa `CORS_ALLOWED_ORIGINS` (default dev, prod sobrescreve via `.env`).
  - **C' — CORS do auth-server MANTIDO e externalizado** (não removível): ao contrário do SPA/BFF, o **Swagger-UI** é um cliente OAuth2 que roda no browser (servido pela borda :8081) e faz **fetch cross-origin** para `/oauth2/token` (:8082) ao trocar o code pelo token → exige CORS no auth-server. `CORSConfig.java` reescrito para ler `@Value("${cors.allowed-origins:http://localhost:8081}")` (origem do Swagger/borda, **distinta** da origem do SPA no gateway); `authorization-server.yml` → `${CORS_ALLOWED_ORIGINS_AUTH:http://localhost:8081}`. Os dois `.cors(...)` do `SecurityConfig` preservados. (Tentativa inicial de remover quebrou o login do Swagger com `TypeError: Failed to fetch` — revertida.)
  - **Validação ao vivo (curl, stack docker):** gateway — preflight Origin permitida 200 + ACAO único, Origin não permitida 403, user-service direto (8090) sem nenhum header CORS, sem `vary` duplicado; auth-server — preflight `Origin: localhost:8081` → `/oauth2/token` libera CORS, `evil.com` → 403. Suítes: user-service 102 + auth-server 19 verdes.

- [x] **C17 — Proteger/segregar o config-server** (ref **G3**). **(1)** porta `8888` (`config-lb`) não publicada em prod (via C16) + **defaults de secret removidos** dos YAMLs servidos (`AUTH_DB_USER`/`AUTH_DB_PASSWORD` em `authorization-server.yml`; `OAUTH_CLIENT_SECRET` ×2 em `gateway.yml`) → sem default, a env ausente no cliente derruba a subida (fail-fast, como C11). **(2) Autenticação HTTP Basic no endpoint:** `spring-boot-starter-security` no config-server + `SecurityConfig` (`/actuator/health` aberto p/ healthchecks, restante autenticado, CSRF off — cliente é máquina); credenciais em `spring.security.user.*` via env. Os 4 clientes enviam `spring.cloud.config.username/password` (lidas em tempo de import, no `application.yml` **local**). Par único `CONFIG_SERVER_USERNAME`/`CONFIG_SERVER_PASSWORD` (default dev no `application.yml`, sem default no compose → fail-fast). mTLS não adotado (Basic atende o alvo de mitigação + isolamento de rede como 2ª camada). · **Prioridade: Média · Esforço: M**

- [x] **C18 — Restringir actuator na borda pública** (ref **G6**). Actuator do gateway movido para uma **porta de management interna** (`management.server.port: 8181`) não publicada no host: a borda externa (8081) deixa de servir `/actuator/**`, fechando o acesso anônimo a `/prometheus` e `/metrics`. O Prometheus raspa por `gateway:8181` na rede interna; healthcheck do gateway ajustado para `8181`. · **Prioridade: Média · Esforço: P**

- [x] **C19 — Lockout / anti-brute-force no login** (ref **G10**). Contador de falhas no Redis por par **(conta, IP)**, **janela fixa** (TTL de 15 min fixado na 1ª falha), lockout após **5 falhas**. Mecânica idiomática: `LoginAttemptService` + `LoginAttemptListener` (conta só `AuthenticationFailureBadCredentialsEvent` de form login) alimentam o contador; `AuthorizationService` devolve `accountNonLocked=false` quando bloqueado → o `DaoAuthenticationProvider` lança `LockedException` antes de checar a senha. Mensagem genérica (sem enumeração); chave `sha256(emailLower|ip)` (sem PII em claro). Parâmetros em `security.lockout.*`. **Pendência de prod:** `server.forward-headers-strategy` + proxy sobrescrevendo `X-Forwarded-For` (senão o IP atrás do proxy colapsa). CAPTCHA e auditoria de eventos (§5) ficam para depois. · **Prioridade: Média · Esforço: M**

- [x] **C8 — `permissions` derivadas das roles** (ref **G8**). `TokenCustomizerConfig` agora deriva `permissions` de `user.getRoles()`: `USER` → `users.read`/`users.write`; `ADMIN` adiciona `users.delete`. `LinkedHashSet` deduplica e mantém ordem estável; preservado o `new ArrayList<>(...)` exigido pela serialização do `JdbcOAuth2AuthorizationService`. · **Prioridade: Média · Esforço: P**

- [ ] **TLS/HTTPS** (ref **G1**) — manter como gap conhecido; decidido configurar junto com a infra de produção. · **Prioridade: Alta · Esforço: M**

## 3. Qualidade e API

- [ ] **C9 — Erros padronizados (RFC 7807 / `ProblemDetail`)**. Hoje o `GlobalExceptionHandler` devolve `String` crua e o `@Valid` sai no formato default do Spring (inconsistente). Unificar 400/404/409/500 + validação num único formato `ProblemDetail`. · **Prioridade: Média · Esforço: M**

- [ ] **C10 — Cobertura de testes desigual**. Gateway só `contextLoads` (e não-hermético), auth-server com `AuthorizationServiceTest` + `UserClientFallbackFactoryTest` (C7) + `LoginAttemptServiceTest` (C19), front-end zero. · **Prioridade: Média · Esforço: M**
  - **Circuit breaker — teste de integração pendente:** `UserClientFallbackFactory` está coberta por unitários, mas falta um teste que simule o `user-service` fora do ar de ponta a ponta. Abordagem recomendada: WireMock (stub do endpoint `/internal/users/email/{email}` retornando 500 ou timeout) + Resilience4j em modo de teste (`slidingWindowSize` mínimo para abrir o circuito rapidamente) + assert que o fallback lança `UsernameNotFoundException` sem timeout. Encaixa no mesmo esforço de C10 quando a infraestrutura de testes do auth-server for montada.
  - **Gateway/auth-server:** roteamento + rate-limit (gateway); `TokenCustomizerConfig` / `JWKConfig` / `SecurityConfig` (auth-server).
  - **`contextLoads` hermético:** hoje o `@SpringBootTest` faz **OIDC discovery real** (via `issuer-uri` do config-server) e falha fora do cenário com auth-server alcançável (ex.: Docker reporta issuer `authorization-server:8082`, teste pede `localhost:8082`). Isolar com `gateway/src/test/resources/application.yml` (sem import do config-server; provider explícito ou autoconfig OAuth2 desabilitada no teste).
  - **Front-end (`login-interface`)** — zero cobertura (só o typecheck do `npm run build`).
    - **Stack:** `vitest` + `@testing-library/react` + `user-event` + `jsdom`; `msw` para simular o gateway; script `"test"` no `package.json` + config em `vite.config.ts`.
    - **Unitários/componente:** `authClient` (`register`; `login`/`logout` disparam redirect), hooks (`useCurrentUser` trata 401 como deslogado; `useRegister` navega no sucesso), componentes (`LoginBox` só botão de redirect; `RegisterBox` mapeia `password`; `ProtectedLayout` redireciona em 401; `NavBar` logout via redirect).
    - **Integração (MSW):** registro + derivação do estado autenticado por `GET /users/me` (200 vs 401), sem `localStorage`.
    - **E2E (opcional):** Playwright cobrindo o redirect OAuth2 ponta a ponta (exige o stack de pé).

- [ ] **C13 — Validação de senha unificada e forte** (ref **G9**). `UserRequestDTO` usa `@Size(min=8)` nullable + checagem manual de null no `RegisterService`, sem regra de complexidade. Unificar na validação declarativa. · **Prioridade: Baixa · Esforço: P**

- [ ] **Versionamento de API (`/v1/...`)** antes de adicionar novas camadas de domínio sobre a base. · **Prioridade: Baixa · Esforço: M**

## 4. Eficiência e operação

- [ ] **C14 — Eliminar a dupla chamada Feign por login**. `AuthorizationService.loadUserByUsername` e `TokenCustomizerConfig.jwtCustomizer` chamam `getUserByEmail` separadamente a cada login. Reaproveitar o resultado (principal/atributo) ou unificar. Mitigado hoje pelo cache `authByEmail`. · **Prioridade: Baixa · Esforço: M**

- [ ] **Pipeline de CI** (ex.: GitHub Actions) — build + testes dos 5 módulos Java + front-end (`npm ci`, `build`, `test` quando a bateria de C10 existir) a cada push/PR. Os testes de integração com Testcontainers exigem Docker no runner. · **Prioridade: Média · Esforço: M**

- [ ] **C15 — Higiene cosmética**. Campos `private` não-`final` + `@Autowired` redundante no construtor (`UserController`, `SearchService`); `RegisterService.updateUser` monta o `UserResponseDTO` duas vezes. · **Prioridade: Baixa · Esforço: P**

## 5. Evolução de domínio (futuro)

> Não imediatos — entram após o sistema de usuários estar estável.

- [ ] Verificação de e-mail no cadastro.
- [ ] Recuperação de senha.
- [ ] **Auditoria de eventos** — registrar logins, alterações de roles e deleções (trilha de auditoria).
- [ ] **Gestão de admin** — hoje criar/promover ADMIN manualmente no MongoDB (`db.users.updateOne({email: ...}, {$addToSet: {roles: "ADMIN"}})`); sem isso as rotas `ROLE_ADMIN` ficam inalcançáveis.
- [ ] Outros microsserviços de negócio sobre esta base.
