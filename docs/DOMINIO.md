# DOMINIO.md — migração do domínio efêmero para domínio fixo via Cloudflare Tunnel

> **Natureza deste documento.** Especificação executável de uma tarefa única, escrita para ser
> seguida por um agente de IA (e lida por humano). Não é documentação de estado — o estado vive em
> [SECURITY.md](SECURITY.md), [CONFIG.md](CONFIG.md) e [CONVENCOES.md](CONVENCOES.md). Quando a
> tarefa for concluída, o conteúdo relevante migra para aqueles documentos e este vira histórico.
>
> **Sigilo.** O domínio real **não** aparece aqui. Em todo o texto, `${PUBLIC_ORIGIN}` designa a
> origem pública (ex.: `https://app.exemplo.com`) e `${PUBLIC_HOST}` o hostname sem esquema. Os
> valores concretos vivem apenas no `.env` (gitignorado).

---

## #1 Tarefa a ser executada

Substituir o **quick tunnel efêmero** (`*.trycloudflare.com`, URL nova a cada restart) por um
**named tunnel da Cloudflare apontado para um subdomínio fixo**, fazendo o fluxo OAuth2/BFF fechar
ponta a ponta em ambiente real; e, na mesma leva, fechar os gaps de segurança que a exposição
pública torna exigíveis.

**Subtarefas (resumo):**

**Principal — domínio + túnel**
1. Criar o named tunnel na Cloudflare (fora do repositório). **Revisado em 2026-08-03:** pela
   **CLI** (`tunnel create`, modo locally-managed), não pelo painel Zero Trust — que exige cartão de
   crédito mesmo no plano free.
2. `CLOUDFLARE_TUNNEL_CREDENTIALS` no esquema de Docker secrets (`gen-secrets.sh`) — o JSON emitido
   pelo `create`, **copiado** e não gerado (era `CLOUDFLARE_TUNNEL_TOKEN`).
3. Reescrever `docker-compose.deploy.yml`: `tunnel run` com `--config`, ingress → `interface:80` via
   `infra/cloudflared/config.yml` **versionado**, `${TUNNEL_ORIGIN}` → `${PUBLIC_ORIGIN}`.
4. Novos `location` no `nginx.conf` do SPA (`/v1/admin`, `/connect/logout`).
5. Nova rota `/connect/logout` no `GatewayRouter` — **exige ADR-018**.
6. Re-seed do `gateway-client` via `down -v` (os redirect URIs atuais são `localhost`).
7. Regenerar **todos** os segredos com valores fortes.

**Secundária — segurança**
8. Remover o `client-secret` entregue ao browser pelo Swagger.
9. Proteger `/swagger-ui/*` e `/v3/api-docs/*` com Cloudflare Access.
10. Despublicar as portas do host na base do compose (restaura a invariante do ADR-010).
11. Headers de segurança no nginx do SPA (CSP, HSTS, Referrer-Policy, Permissions-Policy).
12. Portas de management dedicadas em auth-server / user-service / notification-service.
13. Tier de rate limit dedicado para os deletes administrativos.
14. Validação anti-curinga no CORS dos dois serviços.

**Secundária — higiene**
15. Remover envs mortas; externalizar `AUTH_ISSUER`; adicionar `AUTH_TOKEN` e
    `OAUTH2SWAGGER_REDIRECT_URL` ao overlay.
16. Atualizar testes afetados e a documentação.

---

## #2 Contexto

### Por que esta tarefa existe

O `docker-compose.deploy.yml` atual sobe um **quick tunnel**: `cloudflared tunnel --url
http://gateway:8081`, sem autenticação, com hostname `*.trycloudflare.com` sorteado a cada
inicialização do processo. Isso valida a mecânica de borda (TLS terminado na Cloudflare, cookies
`Secure`, `CF-Connecting-IP` alimentando lockout e rate limit) mas **não fecha o OAuth2**: os
`redirectUri` do `gateway-client` ficam persistidos no Postgres e o seed é idempotente, então uma
URL que muda a cada boot nunca casa com o que está registrado. O `SECURITY.md` descreve esse estado
como "valida a mecânica de borda, mas não cruza a barra de deploy legítimo".

Com domínio próprio, a origem passa a ser estável e o fluxo inteiro fecha.

### Considerações de implementação

**Topologia: hostname único apontando para o nginx do SPA.** O túnel passa a apontar para
`interface:80`, **não** para `gateway:8081`. O container `interface` já serve o SPA e já faz proxy
same-origin de `/v1/users`, `/oauth2`, `/login/oauth2` e `/logout` para o gateway — é exatamente a
topologia que o front-end espera. Consequências que simplificam tudo: **CORS deixa de existir na
prática** (browser e API na mesma origem), os cookies `SESSION`/`XSRF-TOKEN`/`AUTHSESSION` ficam
todos escopados no mesmo host com `SameSite=Lax` funcionando naturalmente, e nem o auth-server nem
o gateway ficam diretamente alcançáveis de fora. O preço é que o nginx precisa ganhar dois
`location` novos e o gateway precisa de uma rota nova.

**A invariante de confiança do ADR-010 é parte da tarefa, não um extra.** O `SECURITY.md` afirma
que `CF-Connecting-IP` só é confiável *"porque apenas o `cloudflared` alcança o gateway/auth na
topologia base"*. Só que o `docker-compose.yml` publica `8081:8081` e `${WEB_HOST_PORT}:80` na
**base** — não no override de dev. Enquanto isso for verdade, qualquer máquina que alcance o host
envia `CF-Connecting-IP: <o que quiser>` e neutraliza o particionamento por IP do rate limit e o
lockout anti-brute-force. Despublicar essas portas na base **não é higiene opcional**: é o que
torna verdadeira a premissa que o resto do controle assume.

**`down -v` é aceitável agora, e só agora.** O seed do `gateway-client` em `OAuth2ClientConfig` é
idempotente sem reconciliação (`findByClientId` → `save` só se ausente): mudar
`OAUTH_CLIENT_REDIRECT_URIS` com o client já persistido **não** atualiza nada. Como não há usuário
real e os segredos precisam ser regenerados de qualquer forma — e `POSTGRES_PASSWORD` /
`MONGO_PASSWORD` só são aplicados na **primeira** inicialização do volume —, zerar tudo resolve os
dois problemas de uma vez. Registre no `SECURITY.md` que essa manobra **não se repete** depois de
haver dados reais; a partir daí, troca de domínio exige `UPDATE` direcionado ou um seed
reconciliador.

**Ordem de execução é obrigatória.** Regenerar segredos → `down -v` → `up` → validar. Subir antes
de regenerar cria volumes com as senhas antigas e obriga a repetir o ciclo.

**Asserção de coerência `PUBLIC_ORIGIN` ↔ `PUBLIC_HOST` (ADR-019).** O `docker-compose.deploy.yml`
inclui um serviço init-container `assert-env` que compara o hostname extraído de `PUBLIC_ORIGIN`
com o valor de `PUBLIC_HOST` e **aborta a subida** se divergirem (código 1, mensagem clara). O
gateway depende dele (`condition: service_completed_successfully`). Não depende de passo manual —
foi justamente a ausência dessa asserção que deixou a divergência invisível e causou o Elo 1.
Antes de subir: confirme que `PUBLIC_HOST` é idêntico ao hostname de `PUBLIC_ORIGIN` sem o `https://`.

**O que não pode regredir** (invariantes do [CLAUDE.md](../CLAUDE.md) e
[CONVENCOES.md](CONVENCOES.md)):
- Cookies de sessão distintos: gateway `SESSION`, auth-server `AUTHSESSION`, com `redisNamespace`
  próprios. Não unificar.
- Canal interno `/internal/**` fora do gateway e do Swagger, protegido por `X-Internal-Token`
  (ADR-006). O túnel **não** pode alcançá-lo.
- `key-prefix` da revogação (`revoke:user:`) **idêntico** nos três serviços (ADR-017).
- `@EnableRedisWebSession` / `@EnableRedisHttpSession` explícitos (Boot 4.0 não autoconfigura).
- Enforcement de `ROLE_ADMIN` só downstream, via `@PreAuthorize` — o gateway **não** ganha
  `hasRole()` (decisão explícita do ADR-014). O tier de rate limit dos deletes **não** viola isso:
  limitar velocidade não é autorizar.

**Escopo deliberadamente fechado.** SMTP real, páginas `/terms`/`/privacy` e ajustes de
observabilidade ficam **fora** desta leva por decisão do operador. Consequência a registrar de forma
explícita: **o sistema não deve receber cadastro de terceiros neste estado.** Sem SMTP o e-mail de
verificação não sai e a conta fica permanentemente inacessível após as 24h de grace period
(ADR-015); sem as páginas de termos, o consentimento obrigatório do ADR-012 é colhido sobre texto
que o titular não consegue ler — base legal frágil sob LGPD. O deploy é para **teste em ambiente
real pelo próprio operador**.

### Achados do levantamento (verificados no código nesta sessão)

Alguns não estão registrados em nenhum documento existente. São a razão de várias subtarefas.

| # | Achado | Onde |
|---|---|---|
| A1 | **`client-secret` do `gateway-client` entregue ao browser** pela config do Swagger UI. O `gateway-client` é cliente **confidencial** do BFF. `use-pkce-with-authorization-code-grant: true` já está ligado — o secret é dispensável. **Verificar antes de remover.** | `config-server/.../config/gateway.yml` (bloco `springdoc.swagger-ui.oauth`) |
| A2 | **`/connect/logout` não tem rota no gateway.** As rotas são só `/v1/users/**`, `/v1/admin/**`, `/oauth2/**`, `/login`, `/v3/api-docs/**`. O overlay aponta `OAUTH_END_SESSION_URI` para lá → **404** na borda pública. Invisível em dev (lá a URL é `localhost:8082` direto). | `gateway/.../routing/GatewayRouter.java` |
| A3 | **`8081:8081` publicado na base** do compose (não no override). Permite forjar `CF-Connecting-IP` da rede local. | `docker-compose.yml:522-523` |
| A4 | **Actuator sem porta dedicada** em auth-server, user-service e notification-service — servem `/actuator/{health,info,metrics,prometheus}` na porta do serviço com `permitAll`. Só o gateway tem `management.server.port: 8181`. Hoje contido porque essas portas não são publicadas. | `authorization-server/.../SecurityConfig.java:89`, `user-service/.../SecurityConfig.java:86` |
| A5 | **`AUTH_ISSUER` hardcoded**, sem `${...}` — única env de identidade não externalizável. | `docker-compose.yml:416` |
| A6 | **Envs mortas:** `OAUTH_GATEWAY_CLIENT` e `OAUTH_SWAGGER_REDIRECT_URL` não são lidas por serviço nenhum (o `CONFIG.md` já registra na nota ³). O nome real é `OAUTH2SWAGGER_REDIRECT_URL`, que o overlay **não** seta; `AUTH_TOKEN` idem → botão *Authorize* do Swagger morre em `localhost:8082`. | `docker-compose.yml:413-414`, `docker-compose.deploy.yml` |
| A7 | **`/terms` e `/privacy` linkadas mas inexistentes** — `try_files` cai no SPA e o router não casa rota → página em branco. | `login-interface/src/components/RegisterBox.tsx:53,57` vs. `login-interface/src/routes/router.tsx` |
| A8 | **`/actuator/**` no `permitAll` do gateway** — morto hoje (actuator na 8181), armadilha se alguém reverter a porta. | `gateway/.../config/SecurityConfig.java` |
| A9 | **Correção de registro do G3:** não é ausência total de headers. O Spring Security já emite `X-Content-Type-Options`, `X-Frame-Options: DENY` e `Cache-Control`. Falta **CSP** (nunca é default) e **HSTS** (não dispara porque a request que chega é HTTP — o TLS termina na Cloudflare). O nginx do SPA não emite nenhum. | `docs/SECURITY.md` (tabela G3), `login-interface/nginx.conf` |
| A10 | **Segredos atuais são os defaults de DEV** do `gen-secrets.sh` — valores públicos num script versionado (`config-dev-secret`, `redis-dev-secret`, `oauth-dev-secret`, `internal-dev-token`, `postgres-dev-secret`, `mongo-dev-secret`, Grafana `admin`). | `infra/secrets/gen-secrets.sh:27-44`, `./secrets/` |
| A11 | **Healthchecks apontam para `/actuator/health` na porta do serviço.** Mover o actuator sem ajustá-los deixa os containers `unhealthy` e derruba toda a cadeia de `depends_on`. | `docker-compose.yml:439` (auth), `:484` (user), `:515` (notification) |

### Informações úteis

**Classes e arquivos que a implementação toca ou precisa conhecer:**

| Arquivo | Papel |
|---|---|
| `gateway/.../routing/GatewayRouter.java` | Rotas em DSL Java. `TokenRelay` é **por rota** (não `default-filters` — a DSL Java não os recebe). Ordem importa: rotas específicas precedem as genéricas |
| `gateway/.../config/SecurityConfig.java` | `permitAll`, CSRF (`/v1/users/register` isento), entry point 401, `oidcLogoutSuccessHandler` (lê `OAUTH_END_SESSION_URI`/`POST_LOGOUT_REDIRECT_URI`), cookies `Secure` via `app.cookie.secure` |
| `gateway/.../config/RateLimiterConfig.java` | Tiers atuais: `redisRateLimiterHigh` (10, 20) `@Primary`, `Med` (5, 10), `Low` (2, 5). `ipKeyResolver` `@Primary` e `userKeyResolver` |
| `gateway/.../config/CORSConfig.java` · `authorization-server/.../config/CORSConfig.java` | `setAllowedOriginPatterns(allowedOrigins)` + `allowCredentials(true)`. Já logam a allowlist efetiva no startup |
| `gateway/.../util/ClientIpResolver.java` · `authorization-server/.../util/ClientIpResolver.java` | Fonte ÚNICA do IP (ADR-010). **Não alterar** |
| `authorization-server/.../config/OAuth2ClientConfig.java` | Seed idempotente do `gateway-client`; lê `oauth.gateway-client.redirect-uris` / `.post-logout-uris` |
| `authorization-server/.../config/SecurityConfig.java` | Duas filter chains (`@Order(1)` OAuth2 endpoints, `@Order(2)` default). `permitAll` em `/actuator/**` na chain 2 |
| `user-service/.../config/SecurityConfig.java` | `permitAll` em `/internal/**` (protegido pelo `InternalTokenFilter`), swagger e actuator |
| `login-interface/nginx.conf` | Proxy same-origin do BFF; `map` de `X-Forwarded-Proto`; `resolver 127.0.0.11` (resolução lazy) |
| `infra/secrets/gen-secrets.sh` | Gera `./secrets/`; defaults de DEV nas linhas 27-44 |
| `infra/prometheus.yml` | Job `microservices` lista os targets por porta — acompanha as portas de management |

**Documentos de referência:** [SECURITY.md](SECURITY.md) (§ Estado atual do deploy, § Gaps
recém-identificados), [CONFIG.md](CONFIG.md) (tabelas de env por serviço), [CONVENCOES.md](CONVENCOES.md),
[TESTES.md](TESTES.md) (gate JaCoCo), [README.md](README.md) § 2b.

**ADRs relevantes:** [005](adr/ADR-005-chave-jwk-persistente.md) (JWK),
[006](adr/ADR-006-canal-interno-isolado.md) (canal interno),
[009](adr/ADR-009-base-secrets-native-docker-secrets.md) (Docker secrets),
[010](adr/ADR-010-resolucao-ip-cliente-confiavel.md) (IP confiável — **central aqui**),
[012](adr/ADR-012-consentimento-lgpd-cadastro.md) (consentimento),
[014](adr/ADR-014-admin-controller-gestao-roles-auditoria.md) (admin),
[015](adr/ADR-015-verificacao-email-cadastro.md) (verificação de e-mail),
[017](adr/ADR-017-revogacao-ativa-token.md) (revogação).

---

## #3 Lista de tarefas a serem executadas

### A — Tarefa principal: domínio fixo + named tunnel

> **Estado (2026-07-28):** **A.1–A.5 implementadas** no repositório. Pendentes: **A.0** (painel da
> Cloudflare) e **A.6** (execução: regenerar segredos → `down -v` → `up`), ambas do operador —
> dependem do domínio e do token reais. Runbook em [README § 2b](../README.md).
>
> **Correções ao spec, verificadas no código nesta sessão:**
> - `/swagger-ui` e `/v3/api-docs` **não tinham `location`** no `nginx.conf` — com o túnel em
>   `interface:80` cairiam no `try_files` do SPA, quebrando o Swagger público que A.0 e A.2
>   pressupõem. Os dois `location` foram adicionados em A.3.
> - A imagem do `cloudflared` é **distroless, sem shell** — `$(cat /run/secrets/...)` não funciona.
>   O binário aceita **`--token-file`** (não só `TUNNEL_TOKEN`), que é o mecanismo usado. Bônus:
>   mantém o token fora da listagem de env do container.
> - **`AUTH_TOKEN` foi para os dois blocos**, não só o `gateway`: `AUTH_URL`/`AUTH_TOKEN` são lidos
>   apenas pelo `OpenAPIConfig` (gateway **e** user-service), e o doc que o Swagger agrega é o do
>   user-service.
> - `OAUTH_CLIENT_REDIRECT_URIS` inclui também `${PUBLIC_ORIGIN}/swagger-ui/oauth2-redirect.html` —
>   sem isso o botão *Authorize* do Swagger não fecha.
> - `interface` **não tem healthcheck** → `depends_on` do cloudflared usa `service_started` para ele
>   e mantém `gateway: service_healthy` para ordem de subida.
>
> **G10 fechado (2026-08-03, ADR-019):** `8081:8081` e `${WEB_HOST_PORT}:80` foram movidos da
> base do compose para `docker-compose.override.yml`. A premissa de confiança do ADR-010 é agora
> verdadeira na topologia base — apenas o `cloudflared` alcança o gateway na base prod-safe.
> **Não regredir:** não mova esses `ports:` de volta para `docker-compose.yml`.

#### A.0 — Pré-requisitos na Cloudflare (fora do repositório, feitos pelo operador)

> ⚠️ **REVISADO EM 2026-08-03 — o modo do túnel mudou.** O painel **Zero Trust exige cadastro de
> cartão de crédito** mesmo no plano free, e a decisão do operador é não cadastrar. Sem Zero Trust
> não há token nem *public hostname* pelo painel — e também **não há Cloudflare Access**. O túnel
> passou a ser **locally-managed**, criado pela CLI. O que segue já reflete o modo novo; o texto
> original está preservado em nota ao final desta subseção.

- [ ] Domínio adicionado à Cloudflare e **nameservers propagados** (caminho crítico — pode levar
      horas; disparar antes de tudo). No registrador: trocar os servidores DNS pelos dois
      nameservers da Cloudflare e **não publicar registros DS** (DNSSEC desligado — zona assinada
      pelo registrador com respostas vindas da Cloudflare dá `SERVFAIL` no domínio inteiro).
- [ ] Named tunnel criado pela **CLI**: `cloudflared tunnel login` → `tunnel create <nome>`.
      Anotar o **UUID** impresso; o `create` grava o *credentials-file* em `~/.cloudflared/<UUID>.json`.
      A imagem é distroless e roda como uid 65532 → o `docker run` precisa de
      `--user "$(id -u):$(id -g)"` **e** `-e HOME=/home/nonroot`, senão o `login` falha ao gravar o
      `cert.pem`. Roteiro completo no [README § 2b](../README.md).
- [ ] `CNAME` de `${PUBLIC_HOST}` criado por `cloudflared tunnel route dns <nome> ${PUBLIC_HOST}`
      (o painel não participa) — conferir no DNS da zona.
- [x] ~~**Cloudflare Access** cobrindo `/swagger-ui/*` e `/v3/api-docs/*`~~ — **INVIÁVEL**: o Access
      é parte do Zero Trust, bloqueado pela mesma exigência de cartão. As duas rotas ficam
      **públicas**; gap registrado em [SECURITY.md](SECURITY.md). Se um dia for habilitado,
      **não** aplicar em `/v1/**` — são chamadas XHR do SPA e quebrariam.

> **Estado não-versionado: resolvido, não aceito.** A dívida prevista aqui ("com token do dashboard,
> as ingress rules vivem no painel") **deixou de existir**: no modo locally-managed o roteamento da
> borda é `infra/cloudflared/config.yml`, versionado. Resíduo remanescente: o `CNAME` de
> `${PUBLIC_HOST}` continua estado da zona Cloudflare (criado por `tunnel route dns`); fechá-lo
> exigiria Terraform.

#### A.1 — Segredo do túnel

- [x] ~~`CLOUDFLARE_TUNNEL_TOKEN` no `gen-secrets.sh`~~ → **substituído** por
      `CLOUDFLARE_TUNNEL_CREDENTIALS` (2026-08-03), que emite `./secrets/CLOUDFLARE_TUNNEL_CREDENTIALS`.
      Diferença em relação a todos os outros segredos: **não é gerado, é copiado** — a variável
      aponta para `~/.cloudflared/<UUID>.json` e o script copia o arquivo. Continua **sem default de
      dev**: ausente → arquivo vazio e o overlay não sobe (fail-fast desejado); caminho informado mas
      inexistente → erro imediato no script.

#### A.2 — `docker-compose.deploy.yml` (reescrita)

- [x] Serviço `cloudflared`: trocar `command: tunnel --no-autoupdate --url http://gateway:8081`
      por execução do named tunnel. **Revisado em 2026-08-03** (modo locally-managed): o comando é
      `tunnel --no-autoupdate --config /etc/cloudflared/config.yml run ${TUNNEL_ID:?}`, com
      `secrets: [CLOUDFLARE_TUNNEL_CREDENTIALS]` e bind-mount `:ro` de
      `infra/cloudflared/config.yml`. O segredo é referenciado pelo `credentials-file:` de dentro do
      config, não por flag.
- [x] **`infra/cloudflared/config.yml` (arquivo novo, 2026-08-03):** ingress rules versionadas. Traz
      só a regra catch-all (`service: http://interface:80`), **sem `hostname:` e sem a chave
      `tunnel:`** — o domínio real não entra no repositório (regra de sigilo deste documento) e o
      compose interpola `${VAR}` no `command` mas **não** dentro de um YAML montado. Daí o ID do
      túnel morar em `TUNNEL_ID` no `.env`. Não há perda de roteamento: só alcança o túnel o
      hostname cujo `CNAME` aponta para ele, e há um só.
- [x] `depends_on`: passa de `gateway` para `interface` (é ele que recebe o tráfego agora). Manter
      `gateway` na cadeia para ordem de subida.
- [x] Substituir todas as ocorrências de `${TUNNEL_ORIGIN}` por `${PUBLIC_ORIGIN}`.
- [x] Bloco `gateway`: manter `APP_COOKIE_SECURE=true` e `SERVER_FORWARD_HEADERS_STRATEGY=framework`;
      `CORS_ALLOWED_ORIGINS`, `OAUTH_AUTHORIZATION_URI`, `OAUTH_REDIRECT_URI`,
      `OAUTH_END_SESSION_URI`, `POST_LOGOUT_REDIRECT_URI`, `API_BASE_URL` → `${PUBLIC_ORIGIN}`.
      **Adicionar** `AUTH_TOKEN: ${PUBLIC_ORIGIN}/oauth2/token` e
      `OAUTH2SWAGGER_REDIRECT_URL: ${PUBLIC_ORIGIN}/swagger-ui/oauth2-redirect.html` (A6).
- [x] Bloco `authorization-server`: manter cookie/forward-headers; `CORS_ALLOWED_ORIGINS_AUTH`,
      `OAUTH_CLIENT_REDIRECT_URIS`, `OAUTH_CLIENT_POST_LOGOUT_URIS` → `${PUBLIC_ORIGIN}`.
      **Remover** `OAUTH_GATEWAY_CLIENT` (env morta, A6).
- [x] Bloco `user-service`: `API_BASE_URL` e `AUTH_URL` → `${PUBLIC_ORIGIN}`.
- [x] Reescrever o cabeçalho do arquivo: o roteiro de 3 passos com placeholder existe **só** por
      causa da URL efêmera. Com named tunnel, `up` único.

#### A.3 — `login-interface/nginx.conf`

- [x] Novo `location /v1/admin` → `gateway:8081`, espelhando o bloco `/v1/users` (mesmos
      `proxy_set_header`).
- [x] Novo `location /connect/` → `gateway:8081` (front-channel do RP-initiated logout).
- [x] Manter `/login/oauth2` e **não** adicionar `/login` puro — `/login` é rota do SPA.
- [x] Headers de segurança (ver B.3) no mesmo arquivo.

#### A.4 — Rota `/connect/logout` no gateway

- [x] `gateway/.../routing/GatewayRouter.java`: rota `connect-logout`, path `/connect/**`,
      `.uri("lb://authorization-server")`, tier **MED** por-IP (é front-channel navegado, não XHR
      autenticado). Posicionar **antes** das rotas genéricas.
- [x] Sem `tokenRelay()` — o `id_token_hint` viaja na query string, não em header.
- [x] **ADR-018** via `/new-adr "rota de logout front-channel na borda"`: mudança de contrato de
      rota exige ADR (regra do CLAUDE.md). Registrar o porquê: sob hostname único, o
      `end_session_endpoint` precisa ser alcançável pela origem pública, e o auth-server não é
      exposto diretamente.

#### A.5 — Envs e `.env`

- [x] `.env.example`: substituir o bloco comentado de URLs públicas por um bloco `PUBLIC_ORIGIN` /
      `PUBLIC_HOST` coerente com a topologia de hostname único. Remover as menções ao
      `docker-compose.tls.yml` e a `docs/TLS_DEV.md` (overlay descontinuado — hoje o texto ainda os
      cita).
- [ ] `.env` (local, não versionado): `PUBLIC_ORIGIN` com o valor real.
- [x] `docker-compose.yml`: `AUTH_ISSUER: ${AUTH_ISSUER:-http://authorization-server:8082}` (A5);
      remover `OAUTH_GATEWAY_CLIENT` e `OAUTH_SWAGGER_REDIRECT_URL` (A6).

#### A.6 — Execução (ordem obrigatória)

- [ ] `docker compose down -v` — zera **todos** os volumes (Postgres, Mongo, Redis).
- [ ] Regenerar segredos com valores fortes:
      `CONFIG_SERVER_PASSWORD=$(openssl rand -hex 32) REDIS_PASSWORD=$(openssl rand -hex 32)
      OAUTH_CLIENT_SECRET=$(openssl rand -hex 32) INTERNAL_API_TOKEN=$(openssl rand -hex 32)
      POSTGRES_PASSWORD=$(openssl rand -hex 32) MONGO_PASSWORD=$(openssl rand -hex 32)
      GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 32)
      CLOUDFLARE_TUNNEL_CREDENTIALS=~/.cloudflared/<UUID>.json
      infra/secrets/gen-secrets.sh`
- [ ] Alinhar `MONGO_PASSWORD` no `.env` ao secret gerado (resíduo 0.3 — o `mongodb-exporter` lê do
      env; se divergir, o exporter fica fora do ar em silêncio). Setar também `PUBLIC_ORIGIN` e
      `TUNNEL_ID` (o UUID de A.0) — sem eles o overlay não interpola e a subida falha.
- [ ] Regenerar o par JWK (`infra/jwk/gen-keys.sh`) — a chave atual assinou tokens de dev.
- [ ] `docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --build`.
- [ ] Validar (seção **Verificação**).

---

### B — Tarefas secundárias

#### B.1 — Swagger: remover o client secret (A1)

- [ ] **Verificar primeiro**, com a stack local no ar:
      `curl -s http://localhost:8081/v3/api-docs/swagger-config | grep -i secret` e
      `curl -s http://localhost:8081/swagger-ui/swagger-initializer.js | grep -i secret`.
- [ ] Remover a linha `client-secret: ${OAUTH_CLIENT_SECRET}` do bloco `springdoc.swagger-ui.oauth`
      em `config-server/.../config/gateway.yml`. O `use-pkce-with-authorization-code-grant: true`
      logo abaixo torna o secret desnecessário.
- [ ] Repetir o `curl` e confirmar saída vazia.
- [ ] Se o secret **estava** exposto: registrar em `SECURITY.md` como achado corrigido, e considerar
      o `OAUTH_CLIENT_SECRET` anterior como comprometido — já será rotacionado em A.6.

#### B.2 — Despublicar as portas do host (A3)

- [ ] `docker-compose.yml`: remover o bloco `ports:` do serviço `gateway` (linhas ~522-523) e do
      serviço `interface` (linhas ~577-578).
- [ ] `docker-compose.override.yml`: adicionar ambos, para dev continuar em `localhost:8081` e
      `localhost:5173`. Atenção ao comentário existente no override sobre listas de `ports:` serem
      **concatenadas** no merge — com a remoção da base, a concatenação deixa de ser problema.
- [ ] Ajustar o comentário do `interface` na base, que hoje explica o `WEB_HOST_PORT`.

#### B.3 — Headers de segurança no nginx (A9)

- [ ] Em `login-interface/nginx.conf`, no bloco `server`, adicionar com `always`:
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains`
  - `Content-Security-Policy` moderada: `default-src 'self'; script-src 'self';
    style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:;
    connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Permissions-Policy: geolocation=(), microphone=(), camera=()`
- [ ] `'unsafe-inline'` em `style-src` é **deliberado** (Tailwind injeta estilo inline). Registrar
      como dívida consciente em `SECURITY.md`, não como descuido.
- [x] Se o Swagger for servido pela origem pública, verificar que a CSP não o quebra — o Swagger UI
      usa estilo inline. Se quebrar, um `location` com CSP relaxada só para `/swagger-ui/` é
      aceitável (~~já está atrás do Access~~). ⚠️ **Revisado em 2026-08-03:** a CSP relaxada foi
      implementada, mas o Access **não existe** (bloqueado pelo Zero Trust) — logo o relaxamento de
      `script-src` hoje vale para uma **rota pública**. Registrado como gap em
      [SECURITY.md](SECURITY.md); saída sem cartão seria Basic auth ou allowlist de IP no nginx.

#### B.4 — Portas de management dedicadas (A4)

- [ ] `config-server/.../config/authorization-server.yml`: `management.server.port: ${MANAGEMENT_PORT:8182}`.
- [ ] `config-server/.../config/user-service.yml`: `${MANAGEMENT_PORT:8190}`.
- [ ] `config-server/.../config/notification-service.yml`: `${MANAGEMENT_PORT:8195}`.
- [ ] **`docker-compose.yml` — healthchecks (A11, crítico):** linhas 439, 484 e 515 apontam para
      `/actuator/health` nas portas 8082/8090/8095. Trocar para as novas portas de management.
      **Esquecer isto deixa os containers `unhealthy` e derruba toda a cadeia de `depends_on`.**
- [ ] `infra/prometheus.yml`, job `microservices`: `authorization-server:8082` → `:8182`,
      `user-service:8090` → `:8190`, `notification-service:8095` → `:8195`
      (`gateway:8181` já está correto).
- [ ] Remover `/actuator/**` do `permitAll` das `SecurityConfig` de auth-server e user-service — na
      porta de management não há filter chain de aplicação, o matcher vira ruído.
- [ ] Remover `/actuator/**` do `permitAll` do gateway (A8).
- [ ] Conferir se algum teste de integração levanta actuator na porta do serviço.

#### B.5 — Tier de rate limit para deletes administrativos (G5)

- [ ] `RateLimiterConfig`: novo bean `redisRateLimiterCritical` — sugestão `new RedisRateLimiter(1, 3)`
      (1 req/s, burst 3). Deletes são operações raras e irreversíveis.
- [ ] `GatewayRouter`: rota `admin-delete` com `.path("/v1/admin/users/**")` e
      `.method(HttpMethod.DELETE)`, usando o tier novo e `userKeyResolver`. Deve **preceder** a
      rota `admin-service` genérica, senão nunca é alcançada.
- [ ] Não mexer no `@PreAuthorize` do `AdminController` — a autorização continua downstream
      (ADR-014).
- [ ] Atualizar `SECURITY.md`: G5 passa de aberto a **parcialmente mitigado** (tier dedicado; 2FA
      segue pendente).

#### B.6 — Validação anti-curinga no CORS (G4)

- [ ] Nos **dois** `CORSConfig` (gateway e authorization-server): no bean, antes de
      `setAllowedOriginPatterns`, rejeitar no startup qualquer origem contendo `*` enquanto
      `allowCredentials(true)` — lançar `IllegalStateException` com mensagem explícita.
- [ ] Fail-fast é o comportamento correto: um CORS curinga com credenciais em produção é
      exfiltração cross-origin, e falhar na subida é melhor que servir aberto.
- [ ] Na topologia de hostname único o CORS deixa de ser exercitado pelo SPA, mas a proteção vale
      contra configuração errada futura.

#### B.7 — Testes

- [ ] `gateway/src/test/.../config/SecurityConfigBeansTest.java` — `END_SESSION` fixa
      `http://localhost:8082/connect/logout` (linhas 37, 120, 132). Continua válido como default;
      conferir se as asserções sobre o path seguem passando.
- [ ] `gateway/src/test/.../integration/GatewayOAuth2FlowIntegrationTest.java:125` — mesma constante.
- [ ] **Novos testes:** rota `/connect/**` presente no `RouteLocator`; tier `Critical` aplicado ao
      DELETE admin e precedência sobre a rota genérica; `CORSConfig` lançando exceção com origem
      curinga (ambos os serviços).
- [ ] Gate JaCoCo: piso 70% LINE/BUNDLE nos 4 módulos de domínio; **80% em classe nova/alterada**
      ([TESTES.md](TESTES.md)).
- [ ] `mvn verify` por módulo + `npm run coverage` no front.

#### B.8 — Documentação

- [x] **`docs/SECURITY.md`** — reescrever § *Estado atual do deploy*: sai o quick tunnel efêmero,
      entra o named tunnel com domínio fixo. Mover o gap **"Sem TLS em prod"** para controles
      ativos. G3 → corrigido no nginx (com a ressalva do `'unsafe-inline'`). G5 → parcialmente
      mitigado. G4 → corrigido. **Adicionar como gaps ativos novos:** SMTP placeholder (bloqueia
      usuário real), `/terms` e `/privacy` ausentes (base legal LGPD), ~~ingress rules do túnel em
      estado não-versionado no painel da Cloudflare~~ → **não se materializou**: o modo
      locally-managed versionou as ingress rules. **Gap novo no lugar dele:** Swagger sem Cloudflare
      Access (`/swagger-ui/*` e `/v3/api-docs/*` públicos).
- [x] **`docs/CONFIG.md`** — `PUBLIC_ORIGIN`, `TUNNEL_ID` e `CLOUDFLARE_TUNNEL_CREDENTIALS` nas
      tabelas; remover as envs mortas e ajustar a nota ³; atualizar a nota do Cloudflare Tunnel
      (o `AUTH_TOKEN` deixa de ser ressalva). *Portas de management: pendente com B.4.*
- [x] **`README.md` § 2b** — substituir o roteiro de 3 passos com placeholder por `up` único, mais o
      roteiro CLI de criação do túnel (incl. a armadilha de uid da imagem distroless).
- [x] **`CLAUDE.md`** — seção de deploy, nova rota do gateway. *Portas de management: pendente com B.4.*
- [x] **`.claude/memory/decisions.md`** — entrada da migração com as decisões e o racional; entrada
      separada em 2026-08-03 para a troca de modo do túnel.
- [ ] **`docs/BLUEPRINT.md`** — se catalogar a borda, refletir a topologia nova.

---

## #4 Checklist

### Pré-requisitos Cloudflare
- [ ] Nameservers do domínio apontados para a Cloudflare e propagados (`dig NS` confirma)
- [ ] DNSSEC **desligado** no registrador — nenhum registro DS (`dig DS` vazio)
- [ ] Named tunnel criado pela **CLI** (`tunnel login` → `tunnel create`); **UUID anotado** e
      `~/.cloudflared/<UUID>.json` no lugar
- [ ] `CNAME` de `${PUBLIC_HOST}` criado por `tunnel route dns` e conferido no DNS
- [x] ~~Access em `/swagger-ui/*` e `/v3/api-docs/*`~~ — **inviável sem cartão** (parte do Zero
      Trust); rotas ficam públicas, gap em `SECURITY.md`

### Principal
- [x] `CLOUDFLARE_TUNNEL_CREDENTIALS` no `gen-secrets.sh`, sem default de dev, **copiado** do JSON
- [x] `infra/cloudflared/config.yml` versionado (catch-all → `interface:80`, sem domínio nem UUID)
- [x] `docker-compose.deploy.yml` reescrito (named tunnel, ingress `interface`, `${PUBLIC_ORIGIN}`,
      `${TUNNEL_ID}`)
- [x] `AUTH_TOKEN` no overlay; `OAUTH_GATEWAY_CLIENT` removido.
      `OAUTH2SWAGGER_REDIRECT_URL` **não** entra — foi removida em 2026-08-04 junto com o bloco
      `springdoc.swagger-ui.oauth` que publicava o client secret (ADR-020). Item superado.
- [ ] `nginx.conf`: `location /v1/admin` e `location /connect/`
- [ ] Rota `/connect/**` no `GatewayRouter`, tier MED, antes das genéricas
- [ ] **ADR-018** escrito e referenciado
- [ ] `.env.example` reescrito; `.env` local com `PUBLIC_ORIGIN`, `PUBLIC_HOST` e `TUNNEL_ID`
- [ ] `AUTH_ISSUER` externalizado; envs mortas removidas
- [ ] `down -v` executado
- [ ] Todos os segredos regenerados com valores fortes
- [ ] Par JWK regenerado
- [ ] `MONGO_PASSWORD` do `.env` alinhado ao secret
- [ ] Stack no ar pelo overlay de deploy

### Secundárias
- [ ] `client-secret` do Swagger **verificado** e removido
- [ ] Portas do host despublicadas na base, republicadas no override
- [ ] Headers de segurança no nginx
- [ ] Portas de management dedicadas nos 3 serviços
- [ ] **Healthchecks do compose ajustados** (A11)
- [ ] `infra/prometheus.yml` atualizado
- [ ] `/actuator/**` fora do `permitAll` nos 3 `SecurityConfig`
- [ ] Tier `Critical` + rota `admin-delete` com precedência
- [ ] Validação anti-curinga nos 2 `CORSConfig`

### Verificação
- [ ] `curl -s ${PUBLIC_ORIGIN}/v3/api-docs/swagger-config | grep -i secret` → **vazio**
- [ ] `curl -I ${PUBLIC_ORIGIN}` → CSP, HSTS, `X-Frame-Options`, `Referrer-Policy` presentes
- [ ] `${PUBLIC_ORIGIN}/` serve o SPA (não 404)
- [ ] **Fluxo completo:** registro → login → `/dashboard` → `POST /logout` → `end_session` no
      auth-server → retorno a `${PUBLIC_ORIGIN}/`
- [ ] `docker compose logs cloudflared` → 4× `Registered tunnel connection`
- [ ] ~~`curl ${PUBLIC_ORIGIN}/swagger-ui/index.html` → tela do Cloudflare Access~~ → hoje serve o
      **Swagger direto** (Access inviável sem cartão). Verificar apenas que carrega e que o
      `Try it out` exige login OAuth2.
- [ ] Do host, em modo prod-like: `curl localhost:8081` → **recusa de conexão**
- [ ] `curl ${PUBLIC_ORIGIN}/internal/users/email/x@y.z` → **404/403** (canal interno inalcançável)
- [ ] `docker compose ps` → todos `healthy`
- [ ] `/actuator/health` responde **só** na porta de management (testar por `docker compose exec`)
- [ ] Prometheus: todos os targets do job `microservices` `UP`
- [ ] Rate limit: repetir `DELETE /v1/admin/users/del/{id}` → **429** antes do que o tier MED daria
- [ ] Lockout: 5 falhas de login pelo domínio → bloqueio; conferir no log que o IP registrado é o
      real do cliente (`CF-Connecting-IP`), não o do `cloudflared`
- [ ] Zipkin: trace de login atravessando gateway → auth-server → user-service, sem span órfão
- [ ] `mvn verify` verde nos 4 módulos de domínio (gate JaCoCo)
- [ ] `npm run coverage` verde no front
- [ ] `docker compose -f docker-compose.yml config` válido (job `compose-validate` do CI)

### Documentação
- [ ] `SECURITY.md` (§ deploy reescrito; G3/G4/G5 movidos; 3 gaps novos registrados)
- [ ] `CONFIG.md` · `README.md` § 2b · `CLAUDE.md` · `.claude/memory/decisions.md`
- [ ] ADR-018 no catálogo de `docs/adr/`

### Registrado explicitamente como fora de escopo
- [ ] SMTP real — **sem isso, não abrir para cadastro de terceiros** (ADR-015, grace period 24h)
- [ ] Proteção do Swagger sem Cloudflare Access (Basic auth ou allowlist de IP no nginx do SPA)
- [ ] Páginas `/terms` e `/privacy` — consentimento do ADR-012 sobre texto ilegível
- [ ] Observabilidade (storage do Zipkin, sampling, senha do Grafana)
- [ ] 2FA/step-up para `ROLE_ADMIN` (G5 permanece parcial)
- [ ] Scan transitivo de dependências (G9)
