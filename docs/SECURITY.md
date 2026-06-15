# Segurança — Controles Ativos e Gaps Conhecidos

Este documento rastreia a **postura de segurança** da v1 do blueprint: os controles **já
implementados** que devem permanecer saudáveis, e os **gaps conhecidos** (dívida aceita
conscientemente) com sua mitigação atual e o que falta para um deploy de produção real.

Em modo manutenção, o objetivo aqui é duplo: **não regredir** os controles existentes e
**não perder de vista** os gaps ao promover o sistema para prod.

## Controles ativos (manter saudáveis)

- **BFF / token fora do browser** (ADR-002): o JWT vive na sessão do gateway (cookie
  `HttpOnly`+`Secure`+`SameSite`); o SPA nunca o vê → XSS não exfiltra JWT/refresh. Não
  reintroduza token no front (`localStorage`/`Authorization: Bearer`).
- **Canal interno isolado** (ADR-006): `/internal/users/email/{email}` protegido por
  `X-Internal-Token` (`InternalTokenFilter`); fora do gateway e do Swagger. Acesso sem o header
  → 403.
- **Lockout anti-brute-force:** `LoginAttemptService` mantém contador de falhas no Redis por par
  **(conta, IP)** — chave `sha256(emailLower|ip)`, janela fixa (TTL 15 min na 1ª falha), lockout
  após 5 falhas (`security.lockout.*`). `LoginAttemptListener` conta só
  `AuthenticationFailureBadCredentialsEvent` de form login; `AuthorizationService` devolve
  `accountNonLocked=false` quando bloqueado → `LockedException` antes da checagem de senha
  (mensagem genérica). **Prod exige** `server.forward-headers-strategy` + proxy sanitizando
  `X-Forwarded-For` (senão o IP é falsificável e o particionamento por IP é burlável).
- **Rate limiting no gateway** (token bucket via Redis): LOW (registro/IP), MED (OAuth2/IP),
  HIGH (autenticados/user).
- **CSRF no gateway** habilitado (`CookieServerCsrfTokenRepository`, cookie `XSRF-TOKEN`;
  `/v1/users/register` isento); entry point devolve **401** (não 302).
- **BCrypt** (custo 10) para hash de senha.
- **Cookies de sessão distintos** por serviço (ADR-007) — evita colisão de sessão.

## Gaps de segurança conhecidos (dívida aceita)

| Gap | Estado / mitigação atual | O que falta para prod |
| --- | --- | --- |
| **Sem TLS em prod** | Curativo: overlay `docker-compose.tls.yml` termina TLS na borda em **dev** (mkcert, `app.localhost`/`auth.localhost`) | Cert ACME + domínios reais; HTTPS ponta a ponta na borda |
| **Chave JWK dev no classpath** | Aceito. Par RSA dev em `src/main/resources/keys/app.{key,pub}` (ADR-005) | Override via `JWK_*` com chave gerada/gerida fora do repo |
| **Grafana `admin/admin`** | Curativo: externalizado para `.env` | Trocar a credencial em prod |
| **Keyfile MongoDB de dev no repo** | Aceito (análogo à chave JWK) | Keyfile gerado/gerido fora do repo em prod |
| **Redis/Sentinel sem autenticação** | Aberto; mitigado por **portas nunca publicadas** no compose base (prod-safe) | `requirepass`/ACL + rede isolada em prod |

## Como manter este documento

- Ao **fechar** um gap, mova-o de "gaps" para "controles ativos" (ou remova) e registre em
  `.claude/memory/decisions.md`.
- Ao **introduzir** dívida de segurança consciente, registre-a aqui com mitigação e caminho de
  saída — dívida não documentada é a que volta a morder.
- Mudanças que afetem contrato/superfície de segurança seguem o fluxo com ADR
  (ver [docs/adr/](adr/) e [docs/CONVENCOES.md](CONVENCOES.md)).
