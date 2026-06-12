# Trabalho Pendente

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Para a visão geral do projeto, arquitetura e convenções, ver [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler](#como-ler)
- [Roadmap](#roadmap)
- [3. Qualidade e API](#3-qualidade-e-api)
- [4. Eficiência e operação](#4-eficiência-e-operação)
- [5. Evolução de domínio (futuro)](#5-evolução-de-domínio-futuro)

## Como ler

- **Contexto:** o sistema roda em **múltiplas instâncias** (escala horizontal) e serve de **base/template reutilizável** pronta para produção.
- **ID `C#`:** âncora estável de cada correção (referenciada por outros docs). Não é reordenável nem reusável.
- **Itens concluídos saem deste arquivo** — a numeração (IDs `C#` e seções `§`) é preservada, não reusada; detalhes no histórico git. Já entregues: **§1 — Resiliência e escalabilidade** inteira (C7 — circuit breaker na chamada Feign; deploy rolling com graceful shutdown + probes; eliminação de SPOFs — Eureka HA, config-server HA, MongoDB replica set, Redis Sentinel), **§2 — Hardening de segurança** inteira (C8, C11, C12, C16, C17, C18, C19 e a borda TLS de dev, curativo do G1), **C13** (validação de senha declarativa e forte, fecha G9), **C15** (higiene cosmética), **C9** (erros padronizados RFC 7807 / ProblemDetail), **C14** (eliminar dupla chamada Feign por login — `userId` embutido como authority `USER_ID:` em `AuthorizationService`, lido diretamente pelo `TokenCustomizerConfig` sem chamada Feign adicional) **C10.1** (integração do auth-server — base `AbstractAuthIntegrationTest` com Testcontainers Postgres+Redis e WireMock como dublê do user-service + 3 testes do fluxo authorization_code+PKCE validando os claims reais do JWT) e **C10.2** (integração do auth-server — lockout C19 e2e, circuit breaker C7, seed idempotente do `gateway-client` e sessão Redis `AUTHSESSION`: 11 testes reusando a infra do C10.1). Os controles ativos resultantes estão em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md#controles-de-segurança-já-implementados).
- **Pendência herdada do §2:** **TLS de produção** (cert ACME/corporativo + domínios reais) — pertence à infra de deploy, não ao código; risco registrado em **G1** ([GAPS_SEGURANCA.md](GAPS_SEGURANCA.md)), setup da borda de dev em [TLS_DEV.md](TLS_DEV.md).
- **Prioridade:** Alta / Média / Baixa (impacto no objetivo de base sólida e multi-instância). **Esforço:** P / M / G.
- **Ref:** gap correlato em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md) — lá fica o **risco/severidade**; aqui, o **plano acionável**.

## Roadmap

Ordem sugerida: **3 → 4 → 5** (a evolução de domínio vem depois da base estável).

| ID    | Item                                                    | Prioridade | Esforço | Ref |
| ----- | ------------------------------------------------------- | ---------- | ------- | --- |
| C20   | Config Resilience4j do Feign ignorada (`instances.*` vs `configs.*`) | Alta | P | — |
| C10.3 | Gateway — `contextLoads` hermético + integração         | Alta       | G       | [TESTES.md](TESTES.md#lacunas-e-cobertura-planejada) |
| C10.4 | Unitários pontuais back-end (3 módulos)                 | Média      | M       | [TESTES.md](TESTES.md#lacunas-e-cobertura-planejada) |
| C10.5 | Front-end (`login-interface`) — setup + bateria         | Média      | M       | [TESTES.md](TESTES.md#lacunas-e-cobertura-planejada) |
| C10.6 | Config-server — HTTP Basic                              | Baixa      | P       | [TESTES.md](TESTES.md#lacunas-e-cobertura-planejada) |
| —     | Pipeline de CI                                          | Média      | M       | —   |

## 3. Qualidade e API

**C10 — Cobertura de testes desigual** (guarda-chuva). Gateway e config-server só `contextLoads` (gateway não-hermético), auth-server com integração completa nas 4 frentes (C10.1 — fluxo OAuth2; C10.2 — lockout, circuit breaker, seed e sessão), front-end zero. Lacunas restantes: C10.3–C10.6. O **mapeamento detalhado dos caminhos** (felizes, erro e borda) de cada fase está em [TESTES.md § Lacunas e cobertura planejada](TESTES.md#lacunas-e-cobertura-planejada) — as fases abaixo trazem o plano de execução, ordenadas por risco/lacuna. Cada fase é dimensionada para uma sessão do `techlead`. **Critério de pronto por fase:** `mvn -f <módulo>/pom.xml test` (ou `npm test`) com BUILD SUCCESS incluindo os novos testes + "Inventário atual" de TESTES.md atualizado (doc-keeper).

- [ ] **C10.3 — Gateway: `contextLoads` hermético + integração.** · **Prioridade: Alta · Esforço: G**
  - **`contextLoads` hermético:** hoje o `@SpringBootTest` faz **OIDC discovery real** (via `issuer-uri` do config-server) e falha fora do cenário com auth-server alcançável (ex.: Docker reporta issuer `authorization-server:8082`, teste pede `localhost:8082`). Isolar com `gateway/src/test/resources/application.yml` (sem import do config-server; provider explícito ou autoconfig OAuth2 desabilitada no teste).
  - **Integração** (`WebTestClient` + Testcontainers `redis` + WireMock como downstream): roteamento + TokenRelay (Bearer chega ao downstream); rate limiting (LOW estourado → 429, buckets por IP/usuário, log do `RateLimitLogFilter`); segurança BFF (sem sessão → 401 não-302; CSRF 403 sem `X-XSRF-TOKEN`; `/v1/users/register` isento); `X-Correlation-ID` propagado/gerado; logout RP-initiated com `id_token_hint`.

- [ ] **C10.4 — Unitários pontuais back-end (3 módulos).** Sem infraestrutura; independente das demais fases — pode ser adiantada se C10.3 bloquear. · **Prioridade: Média · Esforço: M**
  - **auth-server:** `TokenCustomizerConfig` (crítico — zero testes hoje), branch de lockout do `AuthorizationService` (`isBlocked=true` → `accountNonLocked=false`), `LoginAttemptListener` (guard form login vs client auth), `ClientIpResolver`.
  - **user-service:** `CacheService`, `GlobalExceptionHandler` (handlers 400 `IllegalArgumentException` e 500 genérico), `InternalTokenFilter`, `LogUtils.maskEmail`.
  - **gateway:** `CorrelationIdFilter`, `RateLimitLogFilter` (incl. endereço *unresolved* sem NPE), key resolvers do `RateLimiterConfig`.

- [ ] **C10.5 — Front-end (`login-interface`): setup + bateria.** Zero cobertura hoje (só o typecheck do `npm run build`). · **Prioridade: Média · Esforço: M**
  - **Stack:** `vitest` + `@testing-library/react` + `user-event` + `jsdom`; `msw` para simular o gateway; script `"test"` no `package.json` + config em `vite.config.ts`.
  - **Unitários/componente:** `authClient` (`register`, `readCookie`; `login`/`logout` disparam redirect), hooks (`useCurrentUser` trata 401 como deslogado, sem retry; `useRegister` navega no sucesso), componentes (`LoginBox`, `RegisterBox`, `ProfileBox`, `ProtectedLayout` redireciona em 401, `NavBar` logout com `_csrf` do cookie).
  - **Integração (MSW):** registro ponta a ponta + derivação do estado autenticado por `GET /v1/users/me` (200 vs 401), sem `localStorage`.
  - **E2E (opcional):** Playwright cobrindo o redirect OAuth2 ponta a ponta (exige o stack de pé).

- [ ] **C10.6 — Config-server: HTTP Basic (C17/G3).** `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`, sem containers: 401 sem credenciais, 200 com Basic correto, `/actuator/health` aberto. · **Prioridade: Baixa · Esforço: P**

## 4. Eficiência e operação

- [ ] **C20 — Config Resilience4j do Feign ignorada (`instances.*` vs `configs.*`).** Achado do C10.2, verificado empiricamente: com Feign + Spring Cloud CircuitBreaker (group por nome do client), a resolução de configuração só enxerga `resilience4j.*.configs.*` — blocos `instances.user-service` apenas pré-criam um circuit breaker avulso que o Feign **não usa** (o id real é derivado do método, ex.: `IUserClientgetUserByEmailString`). O `config-server/src/main/resources/config/authorization-server.yml` de produção usa `instances.user-service`, então a config do C7 (janela 10, threshold 50%, open 10s, timeout 3s) **provavelmente não está aplicada em produção** — o CB do Feign roda com os defaults do Resilience4j (janela 100, mínimo 100 chamadas, TimeLimiter 1s). **Correção candidata:** trocar `instances:` por `configs:` nesse yml (com `configs.user-service` ou `configs.default`), como já feito no `application.yml` de teste do auth-server. · **Prioridade: Alta · Esforço: P**

- [ ] **Pipeline de CI** (ex.: GitHub Actions) — build + testes dos 5 módulos Java + front-end (`npm ci`, `build`; `test` à medida que as fases C10.1–C10.6 entregarem suas baterias) a cada push/PR. Os testes de integração com Testcontainers exigem Docker no runner. · **Prioridade: Média · Esforço: M**

## 5. Evolução de domínio (futuro)

> Não imediatos — entram após o sistema de usuários estar estável.

- [ ] Verificação de e-mail no cadastro.
- [ ] Recuperação de senha.
- [ ] **Auditoria de eventos** — registrar logins, alterações de roles e deleções (trilha de auditoria).
- [ ] **Gestão de admin** — hoje criar/promover ADMIN manualmente no MongoDB (`db.users.updateOne({email: ...}, {$addToSet: {roles: "ADMIN"}})`); sem isso as rotas `ROLE_ADMIN` ficam inalcançáveis.
- [ ] Outros microsserviços de negócio sobre esta base.
