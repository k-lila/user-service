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
- **IP do cliente não-falsificável (ADR-010):** lockout e rate limiting
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
  (`SESSION`) e auth-server (`AUTHSESSION`), ligada sob TLS pelo overlay `docker-compose.deploy.yml`
  (Cloudflare) — sem assimetria entre os dois.
- **Autenticação no Redis/Sentinel** (ADR-008): os 3 data nodes recebem `--requirepass` e
  `--masterauth`; os 3 sentinels recebem `requirepass` e `sentinel auth-pass mymaster` —
  todos com a mesma `REDIS_PASSWORD` (fail-fast no compose). Os clientes Spring (gateway,
  auth-server, user-service) autenticam via `spring.data.redis.password` (data nodes) e
  `spring.data.redis.sentinel.password` (sentinels). O `redis-exporter` autentica com
  `REDIS_PASSWORD` nos 6 alvos do modo multi-target.
- **Gate de e-mail verificado no login, ativo (ADR-015):** `AuthorizationService.loadUserByUsername`
  mapeia `AuthDTO.emailVerified` para o flag `enabled` do `UserDetails` — `emailVerified=false`
  bloqueia o login via `DisabledException` (mensagem genérica), antes da checagem de senha.
  Desde o `notification-service`, `registerUser` seta `emailVerified=false` no cadastro e
  dispara o e-mail de verificação (`GET /v1/users/verify-email` confirma); `null` (legado,
  anterior ao campo) continua tratado como verificado, sem bloqueio. **Mitigação de conta
  permanentemente inacessível:** janela de carência de 24h (`security.email-verification.
  grace-period`) desde `AuthDTO.registrationDate` — login funciona dentro da janela mesmo sem
  confirmação, caso o e-mail nunca chegue (SMTP down, outbox `FAILED`); só bloqueia de fato
  depois da janela. `registrationDate` é populado só server-side (sem caminho de escrita
  externa) — não há como um atacante estender a própria janela.
- **notification-service: canal interno e anti-abuso (ADR-015):** o endpoint
  `POST /internal/notifications/email-verification` é protegido pelo mesmo `X-Internal-Token`
  do canal interno existente (ADR-006), via `Filter` de servlet simples (sem Spring Security —
  o serviço não tem outra rota autenticável); **nunca exposto pelo gateway**. O reenvio
  (`POST /v1/users/resend-verification`) tem duas camadas de rate limit: tier LOW por IP no
  gateway (mesmo de `/v1/users/register`) **e** um limite por conta-alvo
  (`ResendRateLimitService`, Redis, chave `sha256(emailLower)`, default 3/h) — evita e-mail
  bombing de uma vítima específica via IPs rotativos. A resposta é sempre `202` idêntica
  (anti-enumeração); a chamada ao notification-service é assíncrona, então o branch interno
  não varia a latência observável da resposta HTTP.
- **Token de verificação de e-mail em URL (dívida aceita, ADR-015):** o link de confirmação
  carrega o token na query string (`GET /v1/users/verify-email?token=...`). Mitigado por TTL
  de 15 min + uso único (status do outbox vira `CONFIRMED`/`SUPERSEDED` no primeiro uso válido)
  — padrão de mercado para links de confirmação por e-mail. Confirmado que os filtros de log do
  gateway (`CorrelationIdFilter`, `RateLimitLogFilter`) só logam o path, não a query string —
  o token não aparece em log aplicacional deste repositório. Resíduo fora do código: spans
  Zipkin (`http.url`) e logs de proxy/CDN externos podem capturar a query string completa.
- **Trilha de auditoria de dado pessoal (ADR-011, LGPD):** coleção Mongo
  `auditLogs` (user-service) registra *quem acessou/alterou/apagou qual dado de qual titular,
  quando* — **distinta** do log operacional SLF4J. Cobre mutações (register/update/soft+hard
  delete, grant/revoke de role), leitura de credencial interna (ator SYSTEM) e leitura
  cross-subject (titular ≠ solicitante); o `/me`/leitura do próprio dado não é auditado.
  `targetEmail` mascarado; `correlationId` = traceId B3 (o IP do cliente vive no log de borda do
  gateway, ADR-010). Escrita assíncrona e isolada de falha (dívida consciente detalhada na seção
  **LGPD**). **Consulta via API (ADR-014):** `GET /v1/admin/audit-logs` (feed geral) e
  `GET /v1/admin/users/{id}/audit-logs` (por titular), ambos ADMIN-only e paginados com teto de
  100 itens/página — fecha **parcialmente** a dívida "sem endpoint de consulta" do ADR-011 (a
  trilha ainda não tem TTL/retenção definida).
- **Gestão de roles via API (ADR-014):** `PATCH /v1/admin/users/{id}/roles` (ADMIN-only,
  `@PreAuthorize` no user-service) promove/revoga `ADMIN`/`USER`, elimina a necessidade de
  manipular o MongoDB diretamente em produção e audita `ROLE_GRANT`/`ROLE_REVOKE`. **Bloqueio de
  auto-revogação:** se o ator tentar remover `ADMIN` de si mesmo (checado contra o estado
  persistido no Mongo, não o JWT, que pode estar stale) → **409 Conflict** — evita lockout
  operacional sem rota de recuperação via API.

## Gaps de segurança conhecidos (dívida aceita)

| Gap | Estado / mitigação atual | O que falta para prod |
| --- | --- | --- |
| **Sem TLS em prod** | Curativo: overlay `docker-compose.deploy.yml` (Cloudflare quick tunnel — termina TLS na borda; **valida** a mecânica de borda; URL efêmera **não** cruza a barra) | Named tunnel + domínio (URL estável) ou cert ACME + domínio real — ver _Estado atual do deploy_ |
| **Resíduo 0.3: credencial Mongo do `mongodb-exporter` em env** | Aceito. A imagem `percona/mongodb_exporter` é distroless (sem shell) e não tem flag/`_FILE` para a URI → `MONGO_USER`/`MONGO_PASSWORD` continuam no `.env` (deve casar com `./secrets/MONGO_PASSWORD`). Único segredo fora do Docker secrets. | Imagem wrapper (multi-stage com shell) lendo a URI do secret, ou usuário Mongo de monitoramento de baixo privilégio |
| **Grafana `admin/admin`** | Curativo: senha via Docker secret (`GF_SECURITY_ADMIN_PASSWORD__FILE`) | Trocar a credencial em prod |
| **Keyfile MongoDB de dev no repo** | Aceito (análogo à chave JWK) | Keyfile gerado/gerido fora do repo em prod |
| **TLS de transporte Redis ausente** | Aceito. A senha (`REDIS_PASSWORD`) protege o protocolo de comando mas trafega em claro no handshake `AUTH` na rede interna Docker. Mitigado por portas Redis/Sentinel nunca publicadas no compose base (prod-safe). | TLS no Redis (Redis 6+ `tls-port`) + rede Docker isolada em prod |
| **ACLs por usuário Redis ausentes** | Aceito. Todos os clientes (gateway, auth-server, user-service, exporter) compartilham a mesma `REDIS_PASSWORD` sem segregação de permissões por serviço. | Criar usuários ACL dedicados por serviço com permissões mínimas (Redis 6+) |
| **Janela do token já emitido pós-revogação de role (ADR-014)** | Aceito. `PATCH /v1/admin/users/{id}/roles` evicta `authByEmail` — garante que o **próximo** token emitido reflita as roles novas. Um access token **já emitido** antes da mudança continua válido com as roles antigas até expirar; a janela real é o **TTL do access token**, não o TTL de 5 min do cache. | Revogação ativa de token (introspection/blocklist) |

## Estado atual do deploy (borda Cloudflare)

O deploy é na **própria máquina**, exposto via **Cloudflare Tunnel**. Hoje o estado aceito é o
**quick tunnel efêmero** (`*.trycloudflare.com`, overlay `docker-compose.deploy.yml`):

- **O que isso já entrega:** a Cloudflare termina TLS na borda e o `cloudflared` fala HTTP interno
  com o gateway (nenhuma porta interna publicada). Os controles de borda ficam **reais e validados**:
  cookies `Secure` (`APP_COOKIE_SECURE=true`), `SERVER_FORWARD_HEADERS_STRATEGY=framework` e o IP
  confiável `CF-Connecting-IP` (ADR-010) alimentando lockout/rate-limit. Registro de usuário e Swagger
  funcionam pela URL pública.
- **A limitação aceita por enquanto — URL efêmera.** A URL do quick tunnel **muda a cada reinício**
  do `cloudflared`. Como o OAuth2/BFF depende de URLs de front-channel coerentes (redirect/post-logout
  semeados no Postgres, `issuer-uri`, origens de CORS), o **fluxo OAuth2 ponta a ponta não fecha de
  forma estável** sob URL efêmera. O quick tunnel **valida a mecânica de borda**, mas **não cruza a
  barra** de "deploy legítimo para usuário real".
- **O que será sanado com named tunnel + domínio.** Migrar para **named tunnel + domínio próprio no
  Cloudflare** dá URL pública **estável** e destrava os gaps que hoje ficam parciais por causa da
  efemeridade:
  - **TLS de borda real e estável** (fecha o gap "Sem TLS em prod" para uso real).
  - **CORS/origens fixas** (zero `localhost`, zero URL efêmera) — redirect URIs do OAuth2 estáveis no
    Postgres → **OAuth2 ponta a ponta fecha**.
  - **base-URL estável** para links de e-mail (reset de senha etc., pós-barra).

  **Invariante de confiança (não regredir):** `CF-Connecting-IP` e o HTTP interno só são seguros
  porque **apenas o `cloudflared` alcança** o gateway/auth na topologia base. Expor um serviço direto
  reintroduz spoofing de IP e exige TLS interno.

## LGPD — proteção de dados pessoais

Guardar nome, e-mail e hash de senha de pessoas reais torna o operador **controlador de dados
pessoais** sob a LGPD. Esta seção rastreia a postura do sistema frente aos deveres da lei — os
controles já implementados e o que ainda falta.

| Direito / dever LGPD | Estado | Onde / o que falta |
| --- | --- | --- |
| **Base legal / consentimento** | ✅ Implementado | `termsAccepted` obrigatório (`true`) no cadastro → `consentAcceptedAt` + `termsVersion` na coleção `users` (ADR-012). Aceite versionado permite reconsentimento quando os termos mudarem. |
| **Trilha de auditoria de acesso** | ✅ Implementado | Coleção `auditLogs` registra *quem acessou/alterou/apagou qual dado de qual titular, quando* (ADR-011) — distinta do log SLF4J operacional. Ver "Controles ativos". |
| **Eliminação / direito ao esquecimento** | ✅ Implementado | Self-service: soft-delete (`/remove/me`) e hard-delete (`/delete/me`) da própria conta. A via ADMIN (soft/hard-delete de outro titular) foi removida do `UserController` (ADR-013) e absorvida pelo `AdminController` dedicado (`DELETE /v1/admin/users/{id}` e `.../del/{id}`, ADR-014). |
| **Minimização** | ✅ Implementado | Coleta enxuta (nome, e-mail, senha); PII mascarada em log (`LogUtils.maskEmail`) e em `auditLogs` (`targetEmail` mascarado). |
| **Portabilidade (exportar meus dados)** | ❌ Gap | Falta endpoint para o titular exportar os próprios dados. Pode ser pós-lançamento, mas planejar. |
| **Notificação de incidente** | ⚠️ Parcial | A auditoria (`auditLogs`) e a observabilidade sustentam a investigação; falta **plano** formal de resposta/notificação e alertas (Alertmanager) sobre os SLOs. |

**Dívida da trilha de auditoria (consciente):** escrita assíncrona → risco de perda em crash antes do
flush; a listagem (`GET /v1/users`) não é auditada; sem retenção/TTL definidos. O endpoint de
consulta já existe (`GET /v1/admin/audit-logs` e `.../users/{id}/audit-logs`, ADMIN-only,
[ADR-014](adr/ADR-014-admin-controller-gestao-roles-auditoria.md)) — fecha parcialmente a dívida
original. Detalhe e racional em [ADR-011](adr/ADR-011-trilha-auditoria-dado-pessoal.md).

## Como manter este documento

- Ao **fechar** um gap, mova-o de "gaps" para "controles ativos" (ou remova) e registre em
  `.claude/memory/decisions.md`.
- Ao **introduzir** dívida de segurança consciente, registre-a aqui com mitigação e caminho de
  saída — dívida não documentada é a que volta a morder.
- Mudanças que afetem contrato/superfície de segurança seguem o fluxo com ADR
  (ver [docs/adr/](adr/) e [docs/CONVENCOES.md](CONVENCOES.md)).
