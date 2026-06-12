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
- **Itens concluídos saem deste arquivo** — a numeração (IDs `C#` e seções `§`) é preservada, não reusada; detalhes no histórico git. Já entregues: **§1 — Resiliência e escalabilidade** inteira (C7 — circuit breaker na chamada Feign; deploy rolling com graceful shutdown + probes; eliminação de SPOFs — Eureka HA, config-server HA, MongoDB replica set, Redis Sentinel), **§2 — Hardening de segurança** inteira (C8, C11, C12, C16, C17, C18, C19 e a borda TLS de dev, curativo do G1), **C13** (validação de senha declarativa e forte, fecha G9), **C15** (higiene cosmética), **C9** (erros padronizados RFC 7807 / ProblemDetail), **C14** (eliminar dupla chamada Feign por login — `userId` embutido como authority `USER_ID:` em `AuthorizationService`, lido diretamente pelo `TokenCustomizerConfig` sem chamada Feign adicional) **C10.1** (integração do auth-server — base `AbstractAuthIntegrationTest` com Testcontainers Postgres+Redis e WireMock como dublê do user-service + 3 testes do fluxo authorization_code+PKCE validando os claims reais do JWT), **C10.2** (integração do auth-server — lockout C19 e2e, circuit breaker C7, seed idempotente do `gateway-client` e sessão Redis `AUTHSESSION`: 11 testes reusando a infra do C10.1) e **C10.3** (gateway — `contextLoads` hermético + 34 testes: 23 unitários reativos cobrindo limiters/key resolvers do `RateLimiterConfig`, filtros `CorrelationIdFilter`/`RateLimitLogFilter`, CORS, OpenAPI e beans isolados do `SecurityConfig`; 11 de integração com `WebTestClient` + Testcontainers Redis + WireMock via `lb://` cobrindo roteamento/rewritePath, `X-Correlation-ID`, rate-limit LOW→429 e segurança BFF (401 não-302, isenção de CSRF no `/register`, cookie `XSRF-TOKEN`, preflight CORS, acesso autenticado via `mockJwt`) — cobre também a frente gateway do C10.4; o fluxo BFF OAuth2 ponta a ponta ficou de fora, agora rastreado em **C10.7**), **C10.4** (unitários pontuais de back-end — auth-server: `TokenCustomizerConfig`, branch de lockout do `AuthorizationService`, `LoginAttemptListener`, `ClientIpResolver`; user-service: `CacheService`, os 6 handlers do `GlobalExceptionHandler`, `InternalTokenFilter`, `LogUtils.maskEmail` — 41 testes) e **C10.6** (config-server — HTTP Basic C17/G3 via `@SpringBootTest`+`MockMvc`: 401 sem/errado, 200 com Basic correto, `/actuator/health` aberto; o `contextLoads` foi movido para o pacote correto `com.users.configserver`). Os controles ativos resultantes estão em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md#controles-de-segurança-já-implementados).
- **Pendência herdada do §2:** **TLS de produção** (cert ACME/corporativo + domínios reais) — pertence à infra de deploy, não ao código; risco registrado em **G1** ([GAPS_SEGURANCA.md](GAPS_SEGURANCA.md)), setup da borda de dev em [TLS_DEV.md](TLS_DEV.md).
- **Prioridade:** Alta / Média / Baixa (impacto no objetivo de base sólida e multi-instância). **Esforço:** P / M / G.
- **Ref:** gap correlato em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md) — lá fica o **risco/severidade**; aqui, o **plano acionável**.

## Roadmap

Ordem sugerida: **3 → 4 → 5** (a evolução de domínio vem depois da base estável).

| ID    | Item                                                    | Prioridade | Esforço | Ref |
| ----- | ------------------------------------------------------- | ---------- | ------- | --- |
| C20   | Config Resilience4j do Feign ignorada (`instances.*` vs `configs.*`) | Alta | P | — |
| C10.5 | Front-end (`login-interface`) — setup + bateria         | Média      | M       | [TESTES.md](TESTES.md#lacunas-e-cobertura-planejada) |
| C10.7 | Gateway — fluxo BFF OAuth2 ponta a ponta (TokenRelay + logout RP-initiated) | Média | G | [TESTES.md](TESTES.md#lacunas-e-cobertura-planejada) |
| —     | Pipeline de CI                                          | Média      | M       | —   |

## 3. Qualidade e API

**C10 — Cobertura de testes desigual** (guarda-chuva). auth-server (C10.1/C10.2/C10.4), gateway (C10.3) e user-service com cobertura ampla; config-server com HTTP Basic coberto (C10.6); front-end zero. Lacunas restantes: **C10.5** (front-end) e **C10.7** (fluxo BFF OAuth2 ponta a ponta). O **mapeamento detalhado dos caminhos** (felizes, erro e borda) de cada fase está em [TESTES.md § Lacunas e cobertura planejada](TESTES.md#lacunas-e-cobertura-planejada) — as fases abaixo trazem o plano de execução, ordenadas por risco/lacuna. Cada fase é dimensionada para uma sessão do `techlead`. **Critério de pronto por fase:** `mvn -f <módulo>/pom.xml test` (ou `npm test`) com BUILD SUCCESS incluindo os novos testes + "Inventário atual" de TESTES.md atualizado (doc-keeper).

- [ ] **C10.5 — Front-end (`login-interface`): setup + bateria.** Zero cobertura hoje (só o typecheck do `npm run build`). · **Prioridade: Média · Esforço: M**
  - **Stack:** `vitest` + `@testing-library/react` + `user-event` + `jsdom`; `msw` para simular o gateway; script `"test"` no `package.json` + config em `vite.config.ts`.
  - **Unitários/componente:** `authClient` (`register`, `readCookie`; `login`/`logout` disparam redirect), hooks (`useCurrentUser` trata 401 como deslogado, sem retry; `useRegister` navega no sucesso), componentes (`LoginBox`, `RegisterBox`, `ProfileBox`, `ProtectedLayout` redireciona em 401, `NavBar` logout com `_csrf` do cookie).
  - **Integração (MSW):** registro ponta a ponta + derivação do estado autenticado por `GET /v1/users/me` (200 vs 401), sem `localStorage`.
  - **E2E (opcional):** Playwright cobrindo o redirect OAuth2 ponta a ponta (exige o stack de pé).

- [ ] **C10.7 — Gateway: fluxo BFF OAuth2 ponta a ponta.** Carve-out do C10.3 (a parte mais frágil, exige stub de discovery OIDC/JWK): login real → troca de código → **TokenRelay** entregando `Authorization: Bearer` ao downstream → logout RP-initiated com `id_token_hint`/`end_session_endpoint`. O logout OIDC já tem cobertura unitária (`SecurityConfigBeansTest`); falta o caminho integrado. · **Prioridade: Média · Esforço: G**

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
