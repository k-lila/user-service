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

> Já adequados para escala (não há trabalho pendente): chave JWK persistente (`kid` fixo), estado OAuth em Postgres (JDBC), sessão no Redis (Spring Session), validação JWT stateless, rate limiter e caches no Redis, healthchecks de container + `depends_on` no `docker-compose.yml`.

## 1. Resiliência e escalabilidade

- [ ] **C7 — Circuit breaker na chamada Feign** (auth-server → user-service). Hoje a indisponibilidade do user-service derruba o login (`IUserClient` sem fallback). · **Prioridade: Alta · Esforço: M**
  - `authorization-server/pom.xml` — adicionar `spring-cloud-starter-circuitbreaker-resilience4j`.
  - Criar `clients/UserClientFallbackFactory.java` — `FallbackFactory<IUserClient>` que lança `UsernameNotFoundException` ao acionar o fallback.
  - `clients/IUserClient.java` — adicionar `fallbackFactory = UserClientFallbackFactory.class` no `@FeignClient`.
  - `config-server/.../authorization-server.yml` — `spring.cloud.openfeign.circuitbreaker.enabled=true` + bloco `resilience4j.circuitbreaker.instances.user-service` (`slidingWindowSize`, `failureRateThreshold`, `waitDurationInOpenState`, `permittedNumberOfCallsInHalfOpenState`).

- [ ] **Deploy rolling sem downtime** — `server.shutdown=graceful` + readiness/liveness probes (actuator) em todos os serviços. _(O compose já tem healthchecks de container + `depends_on: service_healthy`; falta o nível de aplicação.)_ · **Prioridade: Média · Esforço: M**

- [ ] **Eliminar SPOFs (infra)** — `discovery-server` em peer replication (Eureka HA), `config-server` replicado, MongoDB replica set e Redis Sentinel/Cluster. · **Prioridade: Média · Esforço: G**

## 2. Hardening de segurança

> Risco e severidade de cada item em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md). Aqui fica o plano de correção.

- [ ] **C16 — Não publicar portas internas em produção** (ref **G2**). Em prod, expor **só o gateway**; remover os `ports:` dos serviços internos no compose de produção (manter na rede interna). Exceção: o auth-server precisa que o browser alcance `/oauth2/authorize` e `/connect/logout` (front-channel) — expor só esses caminhos, não a porta inteira. · **Prioridade: Alta · Esforço: M**

- [ ] **C11 — Secrets fora do `docker-compose.yml`** (ref **G4**, **G11**). Mover Mongo (`user_service:user_1234321`), Postgres (`auth_service:auth_1234321`), `OAUTH_CLIENT_SECRET`, `INTERNAL_API_TOKEN` e Grafana (`admin/admin`) para `.env` git-ignored / secret manager. · **Prioridade: Média · Esforço: P**

- [ ] **C12 — CORS na borda + configurável** (ref **G7**). Hoje há `CORSConfig` em 3 módulos com origens hardcoded; o gateway repassa o `Origin` ao user-service, cuja allowlist já rejeitou `localhost:5173` → 403 + `vary` duplicado. **Estado:** curativo (Opção A — `5173` na allowlist do user-service). · **Prioridade: Média · Esforço: M**
  - **Princípio:** CORS só importa na borda que o browser toca (gateway). Em serviço interno não agrega segurança (o guard real é JWT + isolamento de rede). Pré-condição: confirmar que o user-service **nunca** é chamado direto pelo browser; se for, usar Opção B (gateway remove o header `Origin` em `/users/**` via `RemoveRequestHeader=Origin`).
  - **C — remover CORS do user-service:** apagar o bean `CorsFilter` (`user-service/.../config/CORSConfig.java`) **e** o `.cors(...)` do `user-service/.../config/SecurityConfig.java`; reverter o curativo da Opção A. Verificar que nenhum teste depende do bean.
  - **D — externalizar origens do gateway:** `gateway/.../config/CORSConfig.java` lê de property; `gateway.yml` adiciona `cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}` (CSV → `List<String>`); `docker-compose.yml` seta `CORS_ALLOWED_ORIGINS` quando diferir.
  - **Footguns de D:** (1) `allowCredentials(true)` **proíbe** `allowedOrigins("*")` — usar `setAllowedOriginPatterns(...)` para curinga. (2) Origem errada (scheme/`/` no fim) quebra o SPA com erro difícil → logar a allowlist efetiva no startup; prod **deve** setar a origem real.
  - **C' (próxima rodada):** o auth-server também tem `CORSConfig`, mas o browser **navega** para `localhost:8082` (navegação top-level não é CORS) → provavelmente removível. Avaliar para não deixar o hardening pela metade.
  - **Escopo/validação:** >3 arquivos em 3 módulos → apresentar plano e confirmar antes. CORS só se valida **ao vivo** (rebuild gateway + user-service + browser): re-testar registro, `GET /users/me` e uma chamada mutável. Fazer **depois** do baseline docker estável.

- [ ] **C17 — Proteger/segregar o config-server** (ref **G3**). Não publicar a porta `8888` em prod; proteger o endpoint (Spring Security Basic/mTLS) e **remover os defaults de secret** dos YAMLs (forçar injeção por env). · **Prioridade: Média · Esforço: M**

- [ ] **C18 — Restringir actuator na borda pública** (ref **G6**). Reduzir `management.endpoints.web.exposure.include` no gateway (idealmente só `health`) e exigir auth para `metrics`/`prometheus`, ou raspar por porta/rede de management interna não publicada. · **Prioridade: Média · Esforço: P**

- [ ] **C19 — Lockout / anti-brute-force no login** (ref **G10**). Contador de falhas por conta no Redis (já disponível) com lockout/backoff após N tentativas; considerar CAPTCHA após o limite. Encaixa com a auditoria de eventos (§5). · **Prioridade: Média · Esforço: M**

- [ ] **C8 — `permissions` derivadas das roles** (ref **G8**). Hoje `["users.read","users.write"]` é hardcoded para todo usuário no `TokenCustomizerConfig`; mapear roles → permissions (ex.: ADMIN ganha `users.delete`). · **Prioridade: Média · Esforço: P**

- [ ] **TLS/HTTPS** (ref **G1**) — manter como gap conhecido; decidido configurar junto com a infra de produção. · **Prioridade: Alta · Esforço: M**

## 3. Qualidade e API

- [ ] **C9 — Erros padronizados (RFC 7807 / `ProblemDetail`)**. Hoje o `GlobalExceptionHandler` devolve `String` crua e o `@Valid` sai no formato default do Spring (inconsistente). Unificar 400/404/409/500 + validação num único formato `ProblemDetail`. · **Prioridade: Média · Esforço: M**

- [ ] **C10 — Cobertura de testes desigual**. Gateway só `contextLoads` (e não-hermético), auth-server só `AuthorizationServiceTest`, front-end zero. · **Prioridade: Média · Esforço: M**
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
