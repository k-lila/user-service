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
  (mensagem genérica).
- **IP do cliente não-falsificável (ADR-010, item 1.2 RELATORIOA):** lockout e rate limiting
  resolvem o IP via header confiável `security.trusted-client-ip-header` (default `CF-Connecting-IP`,
  que a Cloudflare **sempre sobrescreve**), com fallback em `getRemoteAddr()`/`getHostString()` sob
  `server.forward-headers-strategy=framework` — agora na **base** do config-server (gateway +
  auth-server), não só nos overlays. O `X-Forwarded-For` bruto deixou de ser lido (sob `cloudflared`,
  que faz *append*, o leftmost é controlado pelo cliente). **Invariante de confiança:** o header só é
  seguro porque só a borda (cloudflared) alcança o gateway/auth na topologia base (portas internas
  nunca publicadas); expor um serviço direto reintroduz o spoofing. Deploy não-Cloudflare deve
  esvaziar/trocar `TRUSTED_CLIENT_IP_HEADER` e garantir que a borda **substitua** (não anexe) o XFF.
- **Rate limiting no gateway** (token bucket via Redis): LOW (registro/IP), MED (OAuth2/IP),
  HIGH (autenticados/user).
- **CSRF no gateway** habilitado (`CookieServerCsrfTokenRepository`, cookie `XSRF-TOKEN`;
  `/v1/users/register` isento); entry point devolve **401** (não 302).
- **BCrypt** (custo 10) para hash de senha.
- **Cookies de sessão distintos** por serviço (ADR-007) — evita colisão de sessão. Ambos
  honram a flag `Secure` parametrizável (`app.cookie.secure`/`APP_COOKIE_SECURE`): gateway
  (`SESSION`) e auth-server (`AUTHSESSION`), ligada sob TLS pelo `docker-compose.tls.yml` —
  sem assimetria entre os dois.
- **Autenticação no Redis/Sentinel** (ADR-008): os 3 data nodes recebem `--requirepass` e
  `--masterauth`; os 3 sentinels recebem `requirepass` e `sentinel auth-pass mymaster` —
  todos com a mesma `REDIS_PASSWORD` (fail-fast no compose). Os clientes Spring (gateway,
  auth-server, user-service) autenticam via `spring.data.redis.password` (data nodes) e
  `spring.data.redis.sentinel.password` (sentinels). O `redis-exporter` autentica com
  `REDIS_PASSWORD` nos 6 alvos do modo multi-target.

## Gaps de segurança conhecidos (dívida aceita)

| Gap | Estado / mitigação atual | O que falta para prod |
| --- | --- | --- |
| **Sem TLS em prod** | Curativo: overlay `docker-compose.tls.yml` termina TLS na borda em **dev** (mkcert, `app.localhost`/`auth.localhost`). Deploy: overlay `docker-compose.deploy.yml` (Cloudflare quick tunnel — **valida** a mecânica de borda; URL efêmera **não** cruza a barra) | Named tunnel + domínio (URL estável) ou cert ACME + domínio real |
| **Resíduo 0.3: credencial Mongo do `mongodb-exporter` em env** | Aceito. A imagem `percona/mongodb_exporter` é distroless (sem shell) e não tem flag/`_FILE` para a URI → `MONGO_USER`/`MONGO_PASSWORD` continuam no `.env` (deve casar com `./secrets/MONGO_PASSWORD`). Único segredo fora do Docker secrets. | Imagem wrapper (multi-stage com shell) lendo a URI do secret, ou usuário Mongo de monitoramento de baixo privilégio |
| **Grafana `admin/admin`** | Curativo: senha via Docker secret (`GF_SECURITY_ADMIN_PASSWORD__FILE`) | Trocar a credencial em prod |
| **Keyfile MongoDB de dev no repo** | Aceito (análogo à chave JWK) | Keyfile gerado/gerido fora do repo em prod |
| **TLS de transporte Redis ausente** | Aceito. A senha (`REDIS_PASSWORD`) protege o protocolo de comando mas trafega em claro no handshake `AUTH` na rede interna Docker. Mitigado por portas Redis/Sentinel nunca publicadas no compose base (prod-safe). | TLS no Redis (Redis 6+ `tls-port`) + rede Docker isolada em prod |
| **ACLs por usuário Redis ausentes** | Aceito. Todos os clientes (gateway, auth-server, user-service, exporter) compartilham a mesma `REDIS_PASSWORD` sem segregação de permissões por serviço. | Criar usuários ACL dedicados por serviço com permissões mínimas (Redis 6+) |

## Como manter este documento

- Ao **fechar** um gap, mova-o de "gaps" para "controles ativos" (ou remova) e registre em
  `.claude/memory/decisions.md`.
- Ao **introduzir** dívida de segurança consciente, registre-a aqui com mitigação e caminho de
  saída — dívida não documentada é a que volta a morder.
- Mudanças que afetem contrato/superfície de segurança seguem o fluxo com ADR
  (ver [docs/adr/](adr/) e [docs/CONVENCOES.md](CONVENCOES.md)).
