---
name: security-scan
description: Varre o projeto (ou um escopo) atrás de regressões de segurança — segredos hardcoded, endpoints internos sem guarda, CSRF/CORS/headers ausentes — e cruza com docs/SECURITY.md. Read-only (não edita). Alimenta o agente security-reviewer. Ex: /security-scan ou /security-scan authorization-server
arguments: [scope]
allowed-tools: Read, Bash, Grep
---

Você vai produzir um **relatório read-only de segurança**. Não edite arquivos.
Distinga sempre **achado novo** (regressão a corrigir) de **gap já aceito** documentado
em `docs/SECURITY.md` (liste como "já conhecido", não como novo).

> `scope` (opcional): nome de um serviço (ex.: `gateway`, `authorization-server`,
> `user-service`) para limitar a varredura. Vazio = todos os módulos back-end.

## Gaps já conhecidos (não reportar como novos)

!`sed -n '/## Gaps de segurança conhecidos/,/## Como manter/p' docs/SECURITY.md 2>/dev/null || echo "(docs/SECURITY.md não encontrado)"`

## Varredura

### 1. Segredos hardcoded (fora de config/env)
!`SCOPE="${scope:-.}"; grep -rnE '(password|secret|token|apikey|api_key|private_key)\s*[:=]\s*"[^"$][^"]{3,}"' --include='*.java' --include='*.yml' "$SCOPE"/src 2>/dev/null | grep -viE 'classpath:|\$\{|test|example|change-?me|REDACTED' | head -40 || echo "(nenhum candidato óbvio)"`

### 2. Endpoint interno e seu guarda
!`echo "--- Usos de /internal:"; grep -rnE '/internal' --include='*.java' user-service/src/main 2>/dev/null; echo; echo "--- Filtro/guarda do token interno:"; grep -rnE 'InternalTokenFilter|X-Internal-Token|internal.api.token' --include='*.java' user-service/src/main 2>/dev/null | head -20`

### 3. CSRF, CORS e cookies na borda
!`echo "--- CSRF / cookies:"; grep -rnE 'csrf|XSRF-TOKEN|CookieServerCsrfTokenRepository|SESSION|AUTHSESSION' --include='*.java' gateway/src/main authorization-server/src/main 2>/dev/null | head -30; echo; echo "--- CORS (procura por wildcard perigoso):"; grep -rnE 'allowedOrigins?|addAllowedOrigin|CORS' --include='*.java' --include='*.yml' gateway/src authorization-server/src 2>/dev/null | grep -iE '\*|allowedorigin' | head -20`

### 4. Manuseio de credenciais e token
!`echo "--- BCrypt / encoder:"; grep -rnE 'BCryptPasswordEncoder|PasswordEncoder|strength' --include='*.java' authorization-server/src/main user-service/src/main 2>/dev/null | head; echo; echo "--- Token no front (NÃO deve haver Bearer/localStorage):"; grep -rnE 'Authorization|Bearer|localStorage' login-interface/src 2>/dev/null | head`

---

## Tarefa — relatório de segurança

Para cada achado, classifique e aponte `arquivo:linha`:

- **BLOQUEADOR** — segredo real exposto, endpoint interno sem `InternalTokenFilter`,
  rota sensível pública, CORS `*` com credenciais, token JWT vazando ao browser.
- **CRÍTICO** — controle enfraquecido (ex.: BCrypt strength reduzido, CSRF desabilitado
  numa rota que não deveria, isenção de CSRF ampliada além de `/v1/users/register`).
- **MELHORIA** — endurecimento desejável.
- **JÁ CONHECIDO** — bate com um gap de `docs/SECURITY.md` (sem TLS prod, JWK dev,
  Redis sem auth, etc.) — não é regressão.

**Saída:** tabela `{ achado, classificação, evidência, ação }`. Conclua com
**"SEGURANÇA: LIMPO"** (só achados já conhecidos/melhorias) ou **"SEGURANÇA: ACHADOS"**
(há BLOQUEADOR/CRÍTICO novo → acionar o `security-reviewer`). Nunca edite arquivos.
