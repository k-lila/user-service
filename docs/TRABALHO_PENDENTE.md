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
- **Itens concluídos saem deste arquivo** — a numeração (IDs `C#` e seções `§`) é preservada, não reusada; detalhes no histórico git. Já entregues: **§1 — Resiliência e escalabilidade** inteira (C7 — circuit breaker na chamada Feign; deploy rolling com graceful shutdown + probes; eliminação de SPOFs — Eureka HA, config-server HA, MongoDB replica set, Redis Sentinel), **§2 — Hardening de segurança** inteira (C8, C11, C12, C16, C17, C18, C19 e a borda TLS de dev, curativo do G1), **C13** (validação de senha declarativa e forte, fecha G9) e **C15** (higiene cosmética). Os controles ativos resultantes estão em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md#controles-de-segurança-já-implementados).
- **Pendência herdada do §2:** **TLS de produção** (cert ACME/corporativo + domínios reais) — pertence à infra de deploy, não ao código; risco registrado em **G1** ([GAPS_SEGURANCA.md](GAPS_SEGURANCA.md)), setup da borda de dev em [TLS_DEV.md](TLS_DEV.md).
- **Prioridade:** Alta / Média / Baixa (impacto no objetivo de base sólida e multi-instância). **Esforço:** P / M / G.
- **Ref:** gap correlato em [GAPS_SEGURANCA.md](GAPS_SEGURANCA.md) — lá fica o **risco/severidade**; aqui, o **plano acionável**.

## Roadmap

Ordem sugerida: **3 → 4 → 5** (a evolução de domínio vem depois da base estável).

| ID  | Item                                            | Prioridade | Esforço | Ref |
| --- | ----------------------------------------------- | ---------- | ------- | --- |
| C9  | Erros padronizados (RFC 7807 / `ProblemDetail`) | Média      | M       | —   |
| C10 | Cobertura de testes (gateway/auth/front)        | Média      | M       | —   |
| —   | Versionamento de API (`/v1`)                    | Baixa      | M       | —   |
| C14 | Eliminar a dupla chamada Feign por login        | Baixa      | M       | —   |
| —   | Pipeline de CI                                  | Média      | M       | —   |

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

- [ ] **Versionamento de API (`/v1/...`)** antes de adicionar novas camadas de domínio sobre a base. · **Prioridade: Baixa · Esforço: M**

## 4. Eficiência e operação

- [ ] **C14 — Eliminar a dupla chamada Feign por login**. `AuthorizationService.loadUserByUsername` e `TokenCustomizerConfig.jwtCustomizer` chamam `getUserByEmail` separadamente a cada login. Reaproveitar o resultado (principal/atributo) ou unificar. Mitigado hoje pelo cache `authByEmail`. · **Prioridade: Baixa · Esforço: M**

- [ ] **Pipeline de CI** (ex.: GitHub Actions) — build + testes dos 5 módulos Java + front-end (`npm ci`, `build`, `test` quando a bateria de C10 existir) a cada push/PR. Os testes de integração com Testcontainers exigem Docker no runner. · **Prioridade: Média · Esforço: M**

## 5. Evolução de domínio (futuro)

> Não imediatos — entram após o sistema de usuários estar estável.

- [ ] Verificação de e-mail no cadastro.
- [ ] Recuperação de senha.
- [ ] **Auditoria de eventos** — registrar logins, alterações de roles e deleções (trilha de auditoria).
- [ ] **Gestão de admin** — hoje criar/promover ADMIN manualmente no MongoDB (`db.users.updateOne({email: ...}, {$addToSet: {roles: "ADMIN"}})`); sem isso as rotas `ROLE_ADMIN` ficam inalcançáveis.
- [ ] Outros microsserviços de negócio sobre esta base.
