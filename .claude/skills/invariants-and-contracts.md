# Skill (referência): Invariantes e contratos

> Documento de conhecimento lido por `senso-critico`, `security-reviewer` e `techlead`, e
> base da skill invocável `/check-compat`. Reúne, em **linguagem de revisão** ("o que
> checar"), as invariantes que **não podem ser quebradas** na v1 do blueprint. O detalhe e
> o racional vivem em [docs/CONVENCOES.md](../../docs/CONVENCOES.md) e a postura de
> segurança em [docs/SECURITY.md](../../docs/SECURITY.md) — aqui não se duplica, aponta-se.

## Superfícies de contrato (verifique a cada mudança)

| Superfície | Produtor | Consumidor | O que quebra |
|---|---|---|---|
| **Feign interno** | `InternalUserController` (`GET /internal/users/email/{email}`, user-service) | `IUserClient` (auth-server) | remover/renomear campo do DTO usado; mudar path/método/status |
| **Claims do JWT** | `TokenCustomizerConfig` (auth-server): `userID`, `roles`, `permissions` | gateway, controllers (`@AuthenticationPrincipal`, `jwt.claim`) | remover/renomear/retipar claim que alguém consome |
| **API pública `/v1/`** | controllers do user-service | gateway, SPA, Swagger | mudar assinatura sem nova versão (`/v2/`); mudar formato de erro `ProblemDetail` |
| **Schema/cache** | entidade `User` (`@Document`), `CacheConfig` | documentos persistidos; caches `usersById`/`usersByEmail`/`authByEmail` | mudança não-retrocompatível no documento ou no DTO cacheado |
| **Borda/sessão** | `GatewayRouter`, `CookieSerializer` | browser, BFF | `TokenRelay` fora da rota certa; cookie `SESSION`/`AUTHSESSION` colidindo; isenção de CSRF ampliada |

→ Mudança em qualquer **produtor** exige conferir todos os **consumidores**. Quebra de
contrato exige **ADR** (`/new-adr`) e versionamento.

## Invariantes que não se quebram (resumo)

- **Separação rígida:** o auth-server **não** acessa MongoDB — só via Feign.
- **Canal interno isolado:** `/internal/...` fora do gateway/Swagger, protegido por
  `X-Internal-Token`; sem header → 403.
- **DELETE com 3 semânticas:** soft-delete ADMIN (`/{id}`), hard-delete ADMIN
  (`/del/{id}`), soft-delete USER (`/remove/me`).
- **Roles fixas** `USER`/`ADMIN`; **BCrypt** custo 10.
- **Cookies de sessão distintos:** `SESSION` (gateway) vs `AUTHSESSION` (auth-server).
- **Spring Session explícito** no Boot 4.0 (`@EnableRedisWebSession`/`@EnableRedisHttpSession`).
- **Resiliência Feign:** todo chamada externa com circuit breaker + timeout + fallback.
- **BFF:** o JWT nunca toca o browser; sem `Authorization`/`localStorage` no front.
- **Config mutável em containers:** padrão copy-to-`/tmp` (Redis Sentinel; MongoDB keyfile
  via override de `entrypoint`).

## Como usar na revisão

1. Rode `/check-compat` para confrontar o diff com as superfícies acima.
2. Para mudança que toca segurança, rode `/security-scan` e acione o `security-reviewer`.
3. Qualquer ❌ de compatibilidade → ADR + nova versão antes de seguir.
