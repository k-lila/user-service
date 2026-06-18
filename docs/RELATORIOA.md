# RELATÓRIO A — Barra de legitimidade de segurança para deploy

> Objetivo: o conjunto **mínimo e suficiente** para que colocar dados de uma pessoa real
> neste sistema seja *legítimo* (técnica e legalmente), não negligente. Organizado por tiers
> de bloqueio, com a dimensão **LGPD** explícita. Aterrado no que está de fato no repositório
> hoje.

## Tier 0 — Bloqueadores absolutos (sem isto, *não* faça deploy público)

| # | Gap | Evidência no repo | Por que é bloqueador | Saída |
|---|---|---|---|---|
| **0.1** | **Chave JWK de assinatura versionada** | `git ls-files` confirma `authorization-server/src/main/resources/keys/app.key` e `app.pub` **rastreados no Git** | Qualquer pessoa com acesso ao repositório tem a **chave privada que assina os JWT**. Isso é **forja de identidade**: dá para emitir um token válido com `roles:["ADMIN"]` e `userID` arbitrário. É o gap mais grave do projeto — anula toda a autenticação. | Gerar par RSA novo **fora do repo**, injetar via `JWK_*` (o mecanismo já existe — ADR-005). Remover as chaves do histórico Git (`git filter-repo`) ou tratar a chave atual como **comprometida e rotacionada**. |
| **0.2** | **TLS na borda ausente em prod** | `docker-compose.tls.yml` é só curativo de dev (mkcert) | Sem HTTPS, senha de login, cookie `SESSION`/`AUTHSESSION` e CSRF token trafegam em claro na internet. Qualquer intermediário captura sessão e credencial. | Certificado ACME (Let's Encrypt) + domínio real terminando TLS na borda. Ligar `APP_COOKIE_SECURE=true` (o flag já é parametrizável, simétrico nos dois serviços). |
| **0.3** | **Segredos em arquivo `.env` plano** | `.env` não versionado (✓ bom), mas contém em texto plano: `OAUTH_CLIENT_SECRET`, `MONGO_PASSWORD`, `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `INTERNAL_API_TOKEN`, `CONFIG_SERVER_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` | Em prod, segredo em arquivo no host é superfície de vazamento (backup, logs de processo, `docker inspect`). Não há rotação. | Secret manager gerenciado (AWS Secrets Manager / GCP Secret Manager / Vault). Mesmo um começo simples: segredos injetados pelo orquestrador, nunca em arquivo no disco. |
| **0.4** | **CORS / origens default de dev** | `CORSConfig` default `http://localhost:8081` | Origem de dev numa allowlist de prod ou esquecida = brecha de CORS. | `cors.allowed-origins` com o domínio real, via env. Auditar que nenhum `localhost` sobrou. |

**Estes quatro definem a linha.** Cruzá-la é o que transforma "demo que roda" em "deploy que
pode receber um usuário real". Note que **nenhum** deles é o Redis interno analisado à parte —
aquilo é Tier 2.

## Tier 1 — Fortemente exigido antes do primeiro usuário real

| # | Item | Estado atual | Ação |
|---|---|---|---|
| **1.1** | **Backup com restore testado** (Mongo + Postgres) | Há HA (replica set / réplicas), **não há backup** | HA protege contra queda de nó, **não** contra exclusão acidental, corrupção lógica ou ransomware. Rotina de backup automatizada **+ um restore de fato testado**. Backup não testado não é backup. |
| **1.2** | **`forward-headers-strategy` + proxy sanitizando `X-Forwarded-For`** | Documentado como "exige em prod", não garantido | Sem isso, o IP é **falsificável** → o particionamento por IP do **lockout** e do **rate limit** é burlável. Os controles existem mas viram teatro. Configurar e validar na topologia de borda. |
| **1.3** | **Reset de senha** | **Ausente** (ver [RELATORIOB.md](RELATORIOB.md)) | Não é só feature — é segurança. Sem fluxo seguro de reset, usuários reutilizam senhas / pedem reset por canais inseguros. Faz parte do mínimo de um sistema de credenciais. |
| **1.4** | **Trilha de auditoria de acesso a dado pessoal** | Há logs operacionais (SLF4J, PII mascarada), **não há audit log** | Exigência de LGPD (ver abaixo) e boa prática: registrar *quem acessou/alterou/apagou qual dado de qual titular, quando*. Distinto do log de aplicação. |
| **1.5** | **Remover credenciais default de dev** | `Grafana admin/admin` (externalizado), `config-client`/`config-dev-secret` no Prometheus scrape | Trocar toda credencial cujo valor default está no repo/docs antes do deploy. |

## Tier 2 — Defesa em profundidade (pós-lançamento, sem bloquear)

São reais, mas pluga-se depois sem migração — **não gaste o orçamento de "agora" aqui**:

- **TLS de transporte + ACLs no Redis** — defesa interna; vale após a borda estar sólida.
  Análise profunda em conversa dedicada (resumo: o tráfego Redis carrega o JWT/refresh na
  sessão do gateway, não só a senha; ACLs exigem cuidado com replicação/Sentinel/keyspace
  notifications).
- **Keyfile MongoDB de dev versionado** — análogo à JWK, mas de impacto interno; gerir fora do repo.
- **Zipkin com storage persistente** (hoje in-memory, perde traces no restart) → Elasticsearch
  externo (parametrização já existe via `ZIPKIN_STORAGE_TYPE`/`ZIPKIN_ES_*`).
- **Alertas** sobre Prometheus — há **dashboards**, mas dashboard não acorda ninguém às 3h.
  Alertmanager com regras sobre os SLOs.
- **Harness de teste de cluster** — os testes usam Redis standalone sem auth/Sentinel
  (`AbstractIntegrationTest` etc.); failover, auth Redis e (futuro) TLS/ACL **não são
  cobertos**. É o gap-mãe por trás dos dois gaps Redis: ambos falham em silêncio, e o CI não pega.

## Dimensão LGPD (não é técnica, é lei — você é o controlador)

Guardar e-mail, nome e hash de senha de pessoas reais te torna **controlador de dados pessoais**
sob a LGPD. O que o projeto já tem e o que falta:

| Direito / dever LGPD | Estado |
|---|---|
| **Eliminação / direito ao esquecimento** | ✅ Já existe (soft/hard delete — ADR-001). Bom. |
| **Base legal / consentimento** | ⚠️ Registrar consentimento no cadastro (aceite de termos/privacidade com timestamp). |
| **Trilha de auditoria de acesso** | ❌ Item 1.4. |
| **Portabilidade (exportar meus dados)** | ❌ Endpoint para o titular exportar os próprios dados. Pode ser pós-lançamento, mas planeje. |
| **Minimização** | ✅ Coleta enxuta (nome, e-mail, senha). PII mascarada em log. Bom. |
| **Notificação de incidente** | ⚠️ Ter um plano (a auditoria do 1.4 e os alertas do Tier 2 sustentam isso). |

**Não precisa resolver tudo no dia 1**, mas o consentimento (cadastro) e a auditoria (1.4)
entram na barra porque nascem com o dado.

## Definição de "deploy legítimo" (checklist de corte)

```
[x] 0.1  JWK fora do repo (+ chave atual rotacionada/tratada como comprometida)   ← FECHADO (2026-06-17)
[~] 0.2  TLS de borda real (ACME) + cookies Secure                                ← PARCIAL (quick tunnel valida; falta named tunnel + domínio)
[~] 0.3  Segredos em secret manager (fora de arquivo plano)                       ← PARCIAL (Docker secrets; ainda arquivo no host)
[~] 0.4  CORS/origens reais, zero localhost                                       ← PARCIAL (via env; efêmero no quick tunnel)
[ ] 1.1  Backup Mongo+Postgres com restore testado
[ ] 1.2  forward-headers + XFF sanitizado (lockout/rate-limit reais)
[ ] 1.3  Reset de senha (RELATORIOB.md)
[ ] 1.4  Audit log de acesso a dado pessoal (LGPD)
[ ] 1.5  Zero credencial default de dev
[ ] LGPD Consentimento registrado no cadastro
```

Fechou os 10 → o deploy é legítimo. Tier 2 vira backlog de hardening contínuo (o "hábito" de
verificar/atualizar/melhorar a segurança continuamente).

## Ordem de ataque

O Tier 0 — sobretudo **0.1 (chave JWK)** — é o único item perigoso **agora**, com o repositório
como está. Trate-o **antes** de embarcar qualquer coisa. O restante do Tier 0 e o Tier 1 compõem
a barra de corte; o Tier 2 é manutenção contínua pós-lançamento.

## Aplicação à decisão: máquina própria + Cloudflare Tunnel

> Decisão tomada: o deploy será na **própria máquina**, exposto à internet via **Cloudflare
> Tunnel** (estratégia de túnel reverso), com **quick tunnel efêmero** (`*.trycloudflare.com`)
> como ponto de partida e **Docker secrets** para os segredos. Esta seção reaterra cada item
> acima nessa realidade concreta — o que muda, o que continua igual e o que a escolha do túnel
> efêmero **não** resolve. O conteúdo dos tiers acima permanece válido; aqui só se especializa.

### Tensão central — o quick tunnel efêmero não cruza a barra

O fluxo OAuth2/BFF depende de **URL pública estável**: o gateway carrega a URL externa em
~7–9 variáveis (`OAUTH_AUTHORIZATION_URI`, `OAUTH_REDIRECT_URI`, `OAUTH_END_SESSION_URI`,
`POST_LOGOUT_REDIRECT_URI`, `CORS_ALLOWED_ORIGINS`, `CORS_ALLOWED_ORIGINS_AUTH`) **e** o
`OAuth2ClientConfig.java` semeia *redirect URIs fixos* no Postgres. Uma URL
`*.trycloudflare.com` **muda a cada reinício** → obrigaria a reconfigurar todas essas
variáveis e re-semear o cliente a cada boot.

**Conclusão honesta:** o quick tunnel **valida a mecânica do túnel** (TLS de borda, cookies
`Secure`, forward-headers, roteamento), mas **não cruza a barra de "deploy legítimo para
usuário real"** — porque os itens **0.2** e **0.4** ficam instáveis. Cruzar a barra exige
**named tunnel + domínio no Cloudflare** (a revisitar quando for receber usuário real).

### Item a item sob a decisão

| Item | Efeito da decisão (home + Cloudflare Tunnel + quick tunnel + Docker secrets) |
|---|---|
| **0.1 JWK** | **Inalterado** — continua obrigatório gerar par novo fora do repo, rotacionar e tratar a chave atual como comprometida. Sob Docker secrets: montar `app.key`/`app.pub` como secret e apontar `JWK_PRIVATE_KEY=file:/run/secrets/jwk_private` / `JWK_PUBLIC_KEY=file:/run/secrets/jwk_public` (props `jwk.*` em `config/authorization-server.yml`, lidas por `JWKConfig.java`). |
| **0.2 TLS** | **O túnel substitui o `docker-compose.tls.yml`/nginx `tls-proxy`** — a Cloudflare termina TLS na borda e o `cloudflared` fala HTTP interno com o gateway. **Mas continuam necessários** `APP_COOKIE_SECURE=true` e `SERVER_FORWARD_HEADERS_STRATEGY=framework`: o browser enxerga HTTPS mesmo com HTTP interno, então os cookies `SESSION`/`AUTHSESSION`/`XSRF-TOKEN` precisam do flag `Secure`. **Ressalva:** com quick tunnel a URL é efêmera → não satisfaz 0.2 para usuário real; só named tunnel + domínio. |
| **0.3 Segredos** | **Docker secrets** no lugar de secret manager gerenciado. Escopo (de `.env.example` + compose): `MONGO_USER/PASSWORD`, `POSTGRES_USER/PASSWORD`, `OAUTH_CLIENT_SECRET`, `INTERNAL_API_TOKEN`, `REDIS_PASSWORD`, `CONFIG_SERVER_USERNAME/PASSWORD`, `GRAFANA_ADMIN_*`, mais os arquivos `JWK_*`, o keyfile Mongo (`infra/mongo/keyfile`) e a senha do config-server **hardcoded** em `infra/prometheus.yml`. **Honestidade:** em Compose sem Swarm, secrets viram arquivos em `/run/secrets/*` — tira o segredo do `.env` plano (e dos backups do `.env`), mas continua arquivo no host. É fechamento **parcial** do 0.3 — registrar como tal, não como resolvido. |
| **0.4 CORS** | `CORS_ALLOWED_ORIGINS` (gateway) e `CORS_ALLOWED_ORIGINS_AUTH` (auth-server) precisam ser a **origem pública do túnel**. Com quick tunnel, mudam a cada boot → mais um motivo do "validação, não prod". |
| **1.1 Backup** | Específico de casa: backup **off-site** (não na mesma máquina nem na mesma residência — incêndio/roubo/ransomware levam original e cópia juntos). Mongo + Postgres, com **restore de fato testado**. |
| **1.2 forward-headers / XFF** | Sob Cloudflare Tunnel a fronteira de confiança passa a ser o `cloudflared`; o IP real do cliente chega via header da Cloudflare (`X-Forwarded-For` / `CF-Connecting-IP`). Exige `SERVER_FORWARD_HEADERS_STRATEGY=framework` para o particionamento por IP do lockout e do rate-limit (`RateLimiterConfig.java` / `RateLimitLogFilter.java`, que leem `X-Forwarded-For` e caem em `getHostString()`). Como **só** o túnel alcança o gateway, o XFF vindo do `cloudflared` é confiável. |
| **1.3 Reset de senha** | Inalterado pela escolha de host — é matéria do [RELATORIOB.md](RELATORIOB.md) (pós-barra). |
| **1.4 Audit log / LGPD** | Inalterado pela escolha de host. |
| **1.5 Defaults de dev** | Inalterado no conteúdo, com destaque para a basic-auth `config-client`/`config-dev-secret` **hardcoded** em `infra/prometheus.yml` (migrar para `password_file`, alinhado ao 0.3) e o Grafana `admin/admin`. |

### Checklist — quick tunnel (agora) vs named tunnel (barra real)

```
Quick tunnel efêmero (trycloudflare) — VALIDA a mecânica:
[ ] cloudflared conecta e roteia até o gateway (sem abrir porta no roteador)
[ ] TLS de borda da Cloudflare funcionando ponta a ponta
[ ] APP_COOKIE_SECURE=true → cookies Secure aceitos pelo browser
[ ] SERVER_FORWARD_HEADERS_STRATEGY=framework → IP real no rate-limit/lockout

Named tunnel + domínio no Cloudflare — DESTRAVA a barra (0.2 + 0.4 reais):
[ ] URL pública estável → OAuth2 (redirect URIs no Postgres) estável
[ ] CORS estável (origens reais, zero localhost, zero URL efêmera)
[ ] links de e-mail (RELATORIOB.md) apontando para base-URL estável
```

A leitura prática: **comece pelo quick tunnel para provar o túnel**, mas só declare a barra
de legitimidade cumprida (itens 0.2/0.4) **com o named tunnel + domínio**.

### Estado atual da execução (2026-06-17)

O que a branch `deploy-a` efetivamente entregou contra o Tier 0 (o detalhamento técnico está em
`.claude/memory/decisions.md` e nos ADRs citados):

- **0.1 JWK — ✅ FECHADO.** A chave de assinatura saiu do versionamento: gerada fora do repo por
  `infra/jwk/gen-keys.sh` (PKCS#8 + X.509), `keys/` no `.gitignore`, o CI gera um par efêmero por
  run, e a chave dev antiga foi rotacionada (tratada como comprometida/inerte). Na base
  secrets-native vira Docker secret (`jwk_private`/`jwk_public`). Formalizado em [ADR-005](adr/ADR-005-chave-jwk-persistente.md).
- **0.3 Segredos — 🟡 PARCIAL (por design).** A base `docker-compose.yml` é agora **secrets-native**
  (Docker secrets via `gen-secrets.sh`, [ADR-009](adr/ADR-009-base-secrets-native-docker-secrets.md)):
  tira o segredo do `.env` plano, mas em Compose sem Swarm continuam **arquivos no host** (sem
  secret manager gerenciado nem rotação). Resíduo: o `mongodb-exporter` (distroless) ainda lê
  `MONGO_*` do `.env`. Cruzar a barra real = Vault / Secret Manager gerenciado.
- **0.2 / 0.4 Borda — 🟡 PARCIAL.** O overlay `docker-compose.deploy.yml` (Cloudflare **quick
  tunnel**) **valida a mecânica** de borda (TLS da Cloudflare, `APP_COOKIE_SECURE=true`,
  `SERVER_FORWARD_HEADERS_STRATEGY=framework`, CORS/URLs via `${TUNNEL_ORIGIN}`). Validado
  manualmente: registro de usuário e Swagger funcionam pela URL pública; o botão **Authorize**
  redireciona mas o **OAuth2 ponta a ponta não fecha** (URL efêmera + redirect URIs idempotentes no
  Postgres). **Não cruza a barra** — exige **named tunnel + domínio fixo**.

**Resumo:** o único item *perigoso agora* (0.1) está resolvido. O que resta do Tier 0 —
0.2/0.4 (named tunnel + domínio) e o fechamento pleno do 0.3 (secret manager) — é a barra para
"deploy legítimo para usuário real".
