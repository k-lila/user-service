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
- **Observabilidade presa ao loopback:** Grafana, Prometheus e Zipkin são publicados como
  `127.0.0.1:PORTA:PORTA` — os três, tanto em dev (`docker-compose.override.yml`) quanto no deploy
  (`docker-compose.deploy.yml`). O bind explícito **é o controle**: sem o IP, o Docker
  publica em `0.0.0.0` e os três passam a responder para toda a rede local. Nenhum deles tem
  lockout, rate limit ou MFA — Prometheus e Zipkin não têm autenticação **nenhuma** — e os dois
  expõem métricas, traces, hostnames internos e topologia. Também **não** há regra de ingress para
  eles no túnel (`infra/cloudflared/config.yml` roteia só `interface:80`), então não são alcançáveis
  de fora. **Não regredir** para `- "3000:3000"`: republica na LAN inteira.
- **Actuator fora da porta de tráfego (fecha o G14):** os **quatro** serviços Spring de domínio
  (gateway, user-service, authorization-server, notification-service) servem `/actuator/**` numa
  porta de management dedicada — `management.server.port: 8181`, hardcoded no YAML de cada um,
  **nunca publicada** em nenhum compose (base, override de dev ou deploy). O Prometheus raspa
  `:8181` pela rede interna e os healthchecks do compose apontam para lá. A porta **é o controle**,
  e é o único: ela não tem autenticação própria. **Não regredir:** publicar `8181` num compose
  reabre o G14. A porta é hardcoded de propósito — como env, um `.env` errado devolveria o actuator
  à porta pública sem ninguém notar.
  > **Armadilha verificada (2026-08-05): `"/actuator/**"` no `permitAll()` dos SecurityConfig
  > NÃO é resíduo — é load-bearing.** A chain de segurança do contexto pai governa **também** a
  > porta de management (o contexto filho herda o filtro). Tentar "limpar" essa linha agora que o
  > actuator saiu da porta de tráfego derruba o próprio actuator: medido no user-service → **401**
  > em `/actuator/health` e `/actuator/prometheus` na 8181 (container `unhealthy`, Prometheus para
  > de raspar); e no authorization-server → **302** para `/login`, com o agravante de o healthcheck
  > continuar **passando** (`curl -f` não falha em 3xx) — falso healthy, métricas mudas.
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
  Desde o `notification-service`, `registerUser` seta `emailVerified=false` no cadastro, mas
  **não** dispara o e-mail automaticamente — o envio só acontece quando explicitamente
  requisitado via reenvio (self ou admin); `GET /v1/users/verify-email` confirma. `null`
  (legado, anterior ao campo) continua tratado como verificado, sem bloqueio. **Mitigação de conta
  permanentemente inacessível:** janela de carência de 24h (`security.email-verification.
  grace-period`) desde `AuthDTO.registrationDate` — login funciona dentro da janela mesmo sem
  confirmação, caso o e-mail nunca chegue (SMTP down, outbox `FAILED`); só bloqueia de fato
  depois da janela. `registrationDate` é populado só server-side (sem caminho de escrita
  externa) — não há como um atacante estender a própria janela.
- **notification-service: canal interno e anti-abuso (ADR-015):** o endpoint
  `POST /internal/notifications/email-verification` é protegido pelo mesmo `X-Internal-Token`
  do canal interno existente (ADR-006), via `Filter` de servlet simples (sem Spring Security —
  o serviço não tem outra rota autenticável); **nunca exposto pelo gateway**. O reenvio deixou
  de ser público/por-e-mail: `POST /v1/users/resend-verification` (self, `ROLE_USER`, resolve o
  titular pelo `userID` do JWT) e `POST /v1/admin/users/{id}/resend-verification` (admin,
  `ROLE_ADMIN`, por `{id}`) — ambos exigem sessão e CSRF como qualquer rota autenticada do
  gateway, eliminando o vetor de anti-enumeração por e-mail que existia antes. Mantém o limite
  por conta-alvo (`ResendRateLimitService`, Redis, chave `sha256(emailLower)`, default 3/h),
  complementar ao rate limit por-usuário/por-IP do gateway. A chamada ao notification-service
  continua assíncrona, então o branch interno não varia a latência observável da resposta HTTP.
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
  delete, grant/revoke de role), leitura de credencial interna (ator SYSTEM) e **leitura
  administrativa de PII por id/e-mail** (`ADMIN_READ_USER`, ADR-016); o `/me`/leitura do próprio
  dado não é auditado. O valor `READ_CROSS_SUBJECT` está `@Deprecated` e não é mais emitido — a
  leitura de PII de terceiro virou ADMIN-only —, mas **permanece no enum** para desserializar
  registros históricos. A *listagem* administrativa (`GET /v1/admin/users`) — desde o ADR-021 a
  única superfície que devolve PII de vários titulares de uma vez — **também é auditada**, com
  `ADMIN_LIST_USERS` e **uma entrada por titular retornado** (fix do G13, ver a seção de gaps).
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
- **Leitura de PII por id/e-mail restrita a ADMIN (ADR-016 — fecha o G1/IDOR):** as rotas
  `GET /v1/users/{id}` e `GET /v1/users/email/{email}` foram **removidas** do `UserController`
  público e reabertas no `AdminController` como `GET /v1/admin/users/{id}` e
  `GET /v1/admin/users/email/{email}` (`@PreAuthorize("hasRole('ADMIN')")`, `AdminUserResponseDTO`
  com `roles`, inclui inativos). Um `USER` não enumera mais PII de terceiro (antes: leitura
  cross-subject auditada mas **não bloqueada**). A leitura do próprio dado permanece em
  `GET /v1/users/me`. Toda leitura admin é auditada com a nova ação `ADMIN_READ_USER` (rastro LGPD
  de *qual admin acessou o dado de qual titular*); `READ_CROSS_SUBJECT` fica `@Deprecated` (não mais
  emitido, mantido para registros históricos). Sem mudança no gateway (rota `/v1/admin/**`).
- **Revogação ativa de token (ADR-017 — fecha o gap de revogação):** um **epoch de revogação por
  usuário** no Redis (`revoke:user:{userID}`, TTL ≥ vida do refresh token) é a fonte única compartilhada
  pelos três serviços. O user-service grava o epoch (junto das evictions de cache) em revogação de role
  (`AdminService.updateUserRoles`), desativação (`RegisterService.deactivateUser`) e hard-delete
  (`RegisterService.deleteUser`) — self **e** admin. Os resource servers rejeitam o token cujo `iat`
  precede o epoch: user-service via `RevocationTokenValidator` (somado aos validadores default no
  `JwtDecoder`); gateway via `RevocationWebFilter` (`GlobalFilter`) que inspeciona o access token da
  sessão e responde **401** + invalida a sessão (defesa em profundidade; o user-service é autoritativo).
  O caminho do refresh é fechado no auth-server: `RevocationRefreshGuard` + `TokenCustomizerConfig`
  abortam o grant `refresh_token` (`invalid_grant`) quando a revogação é mais recente que o refresh token
  apresentado — sem isso o gateway renovaria o access token silenciosamente, perpetuando credenciais
  válidas. **Fail-open** (erro de Redis → não bloqueia; disponibilidade sobre rigor), toggle
  `security.revocation.enabled`. **Invariante:** revogação força re-autenticação (re-login re-deriva
  roles e aplica o gate de e-mail/`active`, ADR-015). Janela residual ≈ segundos (era *indefinida* via
  refresh). `key-prefix` deve casar entre os serviços.

## Gaps de segurança conhecidos (dívida aceita)

> **PISO MÍNIMO NÃO É HA (2026-08-07, ADR-024).** Desde a adoção do piso mínimo, `docker compose
> up` sem argumento sobe **um** nó Mongo, **um** Redis e **um** Sentinel. Isso é elasticidade
> (subir pequeno, crescer depois), **não** redundância: não há failover, e a perda de qualquer um
> dos três derruba o serviço até o restart. A confusão é fácil e cara porque a topologia *parece*
> a de antes — o replica set `rs0` e o Sentinel continuam lá, só que degenerados (um membro, um
> sentinel), justamente para que a configuração dos clientes não mude ao crescer.
>
> **Para qualquer ambiente que precise sobreviver à perda de um nó, suba com `--profile ha`.** Não
> há verificação automática disso: o `up` do piso mínimo é silencioso e bem-sucedido. O único
> sinal é a contagem de containers (19 vs. 26) e `rs.status().members.length` (1 vs. 3).
>
> Vale notar o que **não** mudou: o `auth-postgres` já era e continua sendo instância única nos
> dois pisos — é o SPOF do login (sem ele não há autenticação nova nem refresh), e réplica de
> verdade exige uma segunda máquina. No host único o remédio disponível é backup/PITR, ainda não
> implementado.

> **"Sem TLS em prod": fechado (2026-07-28).** Deixou de ser dívida com a migração para **named
> tunnel + domínio fixo**: a Cloudflare termina TLS numa origem **estável**, e o que antes era
> curativo (quick tunnel de URL efêmera) virou o caminho de deploy real. Ver _Estado atual do
> deploy_. O tráfego interno permanece HTTP por decisão — sustentado pela invariante de que só o
> `cloudflared` alcança a borda interna (ADR-010), **premissa agora verdadeira na topologia base**
> após o fechamento do G10 (2026-08-03, ADR-019): os `ports:` do gateway e da interface foram
> movidos para o override de dev.

> **"Ingress rules do túnel em estado não-versionado": fechado (2026-08-03).** O túnel deixou de ser
> criado pelo painel (token) e passou a ser **locally-managed** (`cloudflared tunnel create` pela
> CLI): o roteamento da borda vive em `infra/cloudflared/config.yml`, versionado, e a autenticação
> é o credentials-file JSON (Docker secret `CLOUDFLARE_TUNNEL_CREDENTIALS`). O repo agora reproduz
> o roteamento da borda e uma mudança nele passa por code review. O gatilho foi contingente — o
> Zero Trust exige cartão de crédito mesmo no plano free —, mas o resultado é exatamente o remédio
> que esta linha previa. **Resíduo:** o `CNAME` de `${PUBLIC_HOST}` continua sendo estado da zona
> Cloudflare (criado por `tunnel route dns`), não versionado; fechá-lo exigiria Terraform.

| Gap | Estado / mitigação atual | O que falta para prod |
| --- | --- | --- |
| **Botão *Authorize* do Swagger inerte** | Resíduo do ADR-020. O `securityScheme` OAuth2 do `OpenAPIConfig` continua no doc (documenta que os endpoints exigem OAuth2), então o botão aparece — mas o bloco `springdoc.swagger-ui.oauth` foi removido, e sem `client-id`/secret preenchidos ele não completa o fluxo. O `Try it out` funciona pela sessão do BFF, não pelo botão. | Nada, se o botão for aceitável como inerte; alternativa é remover o `securityScheme` (perde informação do doc) ou registrar um cliente público `swagger-ui` dedicado |
| **SMTP placeholder — não abrir para cadastro de terceiros** | **Bloqueante para usuário real.** Os secrets SMTP são placeholders de dev (`localhost:1025`, sem auth): o e-mail de verificação não sai. O default é inalcançável **por construção** sob Docker — `localhost` dentro do container é o próprio `notification-service`, e não há MailHog/Mailpit no compose nem override de SMTP em nenhum overlay; um MailHog no host também não seria alcançado. Confirmado empiricamente (2026-08-04): `POST /internal/notifications/email-verification` → **502**, `ConnectException: Connection refused` em `SMTPTransport.openServer`. Toda a cadeia até o SMTP está sadia (Eureka UP, `X-Internal-Token` OK, controller e `EmailService` executam) — o único elo quebrado é a conexão TCP. Como o login exige `emailVerified` após 24h de grace period (ADR-015) e o reenvio é o **único** caminho de envio, uma conta de terceiro fica permanentemente inacessível. **Paradoxo circular do self-service:** passada a janela, `POST /v1/users/resend-verification` é inalcançável — resolve o titular pelo `userID` do JWT e exige sessão, mas o login já está bloqueado por `DisabledException`; sobra **só** o reenvio administrativo (`POST /v1/admin/users/{id}/resend-verification`). Aceito porque o deploy é para teste pelo próprio operador. **Efeito operacional:** o `MailHealthIndicator` faz `testConnection()` a cada scrape → `/actuator/health` responde **503** e o container fica permanentemente `unhealthy`. Não impede o Feign (o Eureka não usa o actuator health por padrão, e o serviço segue `UP` no registro), mas um `depends_on: condition: service_healthy` futuro travaria a subida. | Provedor SMTP real nos 6 secrets (`SMTP_*`) antes de qualquer cadastro externo; para dev, um `mailpit` no compose com `SMTP_HOST` = nome do serviço (nunca `localhost`) |
| **`/terms` e `/privacy` linkadas mas inexistentes** | Aceito no escopo atual, **frágil sob LGPD**. O `RegisterBox.tsx` linka as duas rotas, o router do SPA não as tem e o `try_files` devolve página em branco → o consentimento obrigatório do ADR-012 é colhido sobre texto que o titular não consegue ler. Base legal frágil. | Publicar as duas páginas antes de coletar consentimento de terceiros |
| **Resíduo 0.3: credencial Mongo do `mongodb-exporter` em env** | Aceito. A imagem `percona/mongodb_exporter` é distroless (sem shell) e não tem flag/`_FILE` para a URI → `MONGO_USER`/`MONGO_PASSWORD` continuam no `.env` (deve casar com `./secrets/MONGO_PASSWORD`). Único segredo fora do Docker secrets. | Imagem wrapper (multi-stage com shell) lendo a URI do secret, ou usuário Mongo de monitoramento de baixo privilégio |
| **Grafana sem lockout / rate limit / MFA** | Aceito. A senha vem de Docker secret (`GF_SECURITY_ADMIN_PASSWORD__FILE`), mas **a senha nunca foi o controle suficiente**: o Grafana só tem usuário/senha — o `LoginAttemptService` é do auth-server e o token bucket é do gateway, nenhum dos dois o cobre, e não há MFA. O controle real é a **inalcançabilidade de rede**: porta publicada só em `127.0.0.1` (dev e deploy) e nenhuma regra de ingress no túnel. Expô-lo publicamente colocaria na internet o componente com a autenticação mais fraca do ecossistema. | Se algum dia precisar ser público: SSO OIDC contra o próprio authorization-server com role mapeada (exige `roles` no id_token — hoje `TokenCustomizerConfig` só customiza `access_token`). Para acesso remoto sem superfície pública, malha privada (Tailscale/WireGuard) |
| **Keyfile MongoDB de dev no repo** | Aceito (análogo à chave JWK) | Keyfile gerado/gerido fora do repo em prod |
| **TLS de transporte Redis ausente** | Aceito. A senha (`REDIS_PASSWORD`) protege o protocolo de comando mas trafega em claro no handshake `AUTH` na rede interna Docker. Mitigado por portas Redis/Sentinel nunca publicadas no compose base (prod-safe). | TLS no Redis (Redis 6+ `tls-port`) + rede Docker isolada em prod |
| **ACLs por usuário Redis ausentes** | Aceito. Todos os clientes (gateway, auth-server, user-service, exporter) compartilham a mesma `REDIS_PASSWORD` sem segregação de permissões por serviço. | Criar usuários ACL dedicados por serviço com permissões mínimas (Redis 6+) |

## Gaps recém-identificados (a tratar / não ratificados)

Achados levantados na **auditoria de segurança ad hoc de 2026-06-21** (`security-reviewer`),
**ainda não ratificados** como dívida aceita. Diferente da tabela acima — que registra escolhas
**conscientes** — estes aguardam **correção** ou uma **decisão explícita de aceitação** (quando
um item for tratado, mova-o para "controles ativos"; se for conscientemente aceito, mova-o para a
tabela de dívida aceita). Os controles já ativos **não** regrediram; estes são gaps novos.

> **G1 — IDOR de leitura de PII (ALTO): correção incompleta em 2026-06-21, fechado de fato em
> 2026-08-04 ([ADR-016](adr/ADR-016-leitura-pii-restrita-admin.md) + [ADR-021](adr/ADR-021-remocao-listagem-publica-usuarios.md)).**
> O ADR-016 tornou ADMIN-only as leituras por id/e-mail (`/v1/admin/users/{id}` e
> `.../email/{email}`) e o gap foi declarado fechado — **prematuramente**. `GET /v1/users`
> (`UserController.searchAll`, `hasRole('USER')`) continuou devolvendo `Page<UserResponseDTO>`
> de **toda a base ativa** a qualquer usuário autenticado, **sem auditoria**: o mesmo desfecho
> que o G1 descrevia, obtido com uma requisição paginada em vez de enumeração por id. A rota foi
> removida pelo ADR-021, junto de `SearchService.searchAll` e `IUserRepository.findByActiveTrue`.
> Agora sim: nenhuma superfície `USER` devolve PII de terceiro.
>
> **Por que ficou aberto seis semanas — o que não repetir.** O ADR-016 enumerou as rotas que
> conhecia em vez de varrer a superfície do controller. A partir daí três documentos (este, o
> `CLAUDE.md` e as _Consequências_ do próprio ADR-016) passaram a afirmar que o gap estava
> fechado, e o `BLOCK-004` chegou a ser marcado RESOLVIDO **com base nesses documentos, não no
> código** — a documentação validando a si mesma. Só o `docs/SERVICOS.md` continuou correto,
> porque documentava a rota. **Critério de "fechado" verifica-se contra o código, nunca contra
> outro documento.**

> **G3 — Sem headers de segurança HTTP (MÉDIO): corrigido (2026-07-28).** Correção de registro: não
> era ausência total — o Spring Security já emitia `X-Content-Type-Options`, `X-Frame-Options: DENY`
> e `Cache-Control`. Faltavam **CSP** (nunca é default) e **HSTS** (não dispara porque a request que
> chega ao gateway é HTTP — o TLS termina na Cloudflare), e o nginx do SPA não emitia nenhum. Agora
> `login-interface/nginx.conf` emite, com `always`: `Strict-Transport-Security`,
> `Content-Security-Policy`, `X-Content-Type-Options`, `Referrer-Policy` e `Permissions-Policy`.
> **Dívida consciente embutida:** `'unsafe-inline'` em `style-src` é deliberado (o Tailwind injeta
> estilo inline); e o `location /swagger-ui` roda uma CSP própria que relaxa também `script-src`,
> porque o Swagger-UI usa script inline. A mitigação prevista era o Cloudflare Access na frente
> dele — que nunca ficou ativo (exige cartão). **Atualização (2026-08-04, ADR-020):** a rota deixou
> de ser pública — exige sessão OAuth2 —, então o relaxamento de `script-src` não vale mais para
> anônimos.
> Atenção ao mexer: no nginx, um `location` com `add_header` próprio **descarta** todos os headers
> do nível `server`, por isso o bloco do Swagger repete a lista inteira.

> **Canal interno do notification-service publicado em OpenAPI (MÉDIO): fechado (2026-08-04,
> [ADR-021](adr/ADR-021-remocao-listagem-publica-usuarios.md)).** O módulo trazia
> `springdoc-openapi-starter-webmvc-ui` no classpath e o YAML servido desligava apenas
> `springdoc.swagger-ui.enabled` — `/v3/api-docs` seguia servindo a especificação de
> `POST /internal/notifications/email-verification` a quem alcançasse a porta 8095 (publicada em
> `0.0.0.0` no override de dev; o `InternalTokenFilter` cobre só `/internal/*`). Violava a
> invariante do ADR-006. **Fechamento:** dependência removida do `pom.xml` — garantia de
> *classpath*, não de propriedade. Desligar `springdoc.api-docs.enabled` por YAML seria
> condicional: o serviço importa a config com `optional:configserver:` e a propriedade evapora se
> o config-server estiver fora no boot. **Não reintroduzir a dependência.** Regra geral em
> `docs/CONVENCOES.md`: serviço que publica doc esconde a rota interna com `@Hidden`; serviço que
> não publica não tem a dependência.

> **Lockout alimentado por indisponibilidade do user-service (MÉDIO, DoS auto-infligido): fechado
> (2026-08-04, [ADR-021](adr/ADR-021-remocao-listagem-publica-usuarios.md)).** O
> `UserClientFallbackFactory` lançava `UsernameNotFoundException` quando o circuito abria; o
> `DaoAuthenticationProvider` a convertia em `BadCredentialsException` **sem encadear a causa**, o
> publisher emitia `AuthenticationFailureBadCredentialsEvent` e o `LoginAttemptListener`
> incrementava o contador — **cinco tentativas durante um outage bloqueavam o par (conta, IP) por
> 15 minutos**. Um incidente de infraestrutura virava negação de serviço para o usuário legítimo,
> e o comentário de intenção do próprio listener dizia o contrário. **Fechamento:** exceção
> dedicada `UserServiceUnavailableException extends InternalAuthenticationServiceException`, que o
> provider repropaga intacta e o publisher não mapeia — nenhum evento, nenhum contador. Detalhe
> que **não pode regredir**: a exceção **não encadeia `cause`**, porque o failure handler a guarda
> na sessão Redis e a cadeia Feign/Resilience4j não é serializável (a primeira versão do fix
> quebrou 3 testes de integração com `SerializationException`). Guard: o teste de cruzamento
> `naoDeveBloquearConta_apos5FalhasDuranteOutage`.
>
> **Nuance obrigatória (correção pós-revisão, mesmo dia):** só a **indisponibilidade real** escapa
> do contador. O **404** (titular inexistente ou inativo) é resultado de negócio e **conta no
> lockout** — o `UserClientFallbackFactory` distingue as duas causas por
> `instanceof FeignException.NotFound`. A primeira versão do fix não distinguia e tirou o
> not-found do contador junto, enfraquecendo o atrito contra **enumeração de e-mails**. Ao mexer
> aqui: `instanceof FeignException` **genérico** devolveria 500/503 ao lockout e reabriria o bug
> original — só `NotFound` isola o caso de negócio.

> **DoS por typo no login (MÉDIO, pré-existente): fechado (2026-08-04, ADR-021 pós-revisão).**
> O 404 de "e-mail não encontrado" contava como falha do circuit breaker. Com `slidingWindowSize`
> 10 e `failureRateThreshold` 50%, um punhado de logins com e-mail digitado errado abria o circuito
> e derrubava o login de **todos** por 10s — sem nenhuma falha real de infraestrutura. Fechado com
> `ignoreExceptions: [feign.FeignException$NotFound]` em `configs.user-service`
> (`config-server/.../authorization-server.yml`), primeira ocorrência de `ignoreExceptions` no
> projeto. **Não é substituto do `instanceof` no fallback:** `ignoreExceptions` só tira o 404 da
> contabilidade do circuito; o fallback continua sendo invocado (o `getAndApplyFallback` do Spring
> Cloud captura `Throwable` sem filtro). As duas correções são interdependentes.

> **G14 — `/actuator/**` sem guarda fora do gateway (MÉDIO): fechado (2026-08-05).**
> Só o gateway isolava o actuator numa porta de management própria. Nos outros três o
> `/actuator/**` respondia na **porta de tráfego**: `permitAll()` explícito no
> `authorization-server` (8082) e no `user-service` (8090), e no `notification-service` (8095) sem
> guarda alguma — o serviço não tem Spring Security e o `InternalTokenFilter` cobre só
> `/internal/*`. Qualquer container da rede (R-09) e, sob o override de dev, qualquer host da LAN
> lia `/actuator/metrics` e `/actuator/prometheus`: topologia, hostnames internos e volume de
> tráfego. **Fechamento:** `management.server.port: 8181` nos três (mesmo padrão e mesma porta do
> gateway — containers distintos, sem colisão), porta **não publicada** em nenhum compose;
> `infra/prometheus.yml` raspa `:8181` nos quatro; healthchecks do compose apontam para 8181.
> **Medido, não deduzido:** antes, de qualquer container da rede, `/actuator/health` respondia
> **200** anônimo nos três e `/actuator/prometheus` devolvia o dump completo de métricas; depois,
> a porta de tráfego responde 404/500 e o corpo não carrega métrica alguma.
>
> **Tentativa de defesa em profundidade que foi revertida — e por quê.** A intenção era também
> tirar `"/actuator/**"` do `permitAll()` dos SecurityConfig, para que reverter a porta não
> reabrisse o acesso anônimo em silêncio. **Não funciona:** a chain do contexto pai governa
> também a porta de management, então remover a linha derruba o actuator na 8181 (user-service
> 401 + `unhealthy`; auth-server 302 com healthcheck em falso-positivo). Revertido, e a linha
> ficou **anotada nos três arquivos** como load-bearing. Registro do custo: a hipótese "essa linha
> é resíduo inerte" veio de leitura estática e só caiu ao subir o sistema — é o tipo de premissa
> que teste de unidade não pega, porque nenhum teste sobe a porta de management.
>
> **Correção de escopo registrada junto.** O gap foi escrito nomeando **dois** serviços; o
> `user-service` estava na mesma situação (`SecurityConfig:86`) e ficou de fora. O achado não era
> novo — `security-reviewer` e `senso-critico` o registraram em `.claude/memory/decisions.md`
> (2026-08-04, "G14 deve incluir o `user-service`") e ele **nunca chegou a este documento**. É a
> variante do post-mortem do G1: lá a doc afirmava fechado o que o código deixava aberto; aqui a
> doc descrevia um gap menor do que o real. Mesma lição — **o escopo de um gap verifica-se
> varrendo a superfície no código, não relendo o texto do gap**.
>
> **Severidade em perspectiva (não regredir a análise):** a exposure dos quatro serviços sempre
> foi só `health, info, metrics, prometheus` — nunca `env`, `heapdump`, `threaddump` ou `beans`.
> Não havia vazamento de segredo ou de memória; o gap era de reconhecimento. **Resíduo aceito:** a
> porta de management não tem autenticação — o controle é ela não ser publicada e a rede Docker ser
> interna. Numa rede compartilhada com terceiros (o que R-09 já cobre), isso não bastaria.
> **Não afetado:** os discovery-servers seguem servindo o actuator na porta principal (9091/9092),
> e o config-server tem controle próprio (HTTP Basic, com `/actuator/health` aberto).

> **G13 — Listagem administrativa não auditada (MÉDIO): fechado (2026-08-05).**
> `GET /v1/admin/users` devolvia PII paginada de vários titulares sem emitir nada na trilha LGPD
> — só as leituras por id/e-mail auditavam. Desde o ADR-021 é a **única** superfície de listagem
> do sistema, então um ADMIN (ou um token ADMIN comprometido) exfiltrava a base inteira sem
> rastro. **Fechamento:** ação nova `ADMIN_LIST_USERS`, emitida por
> `AuditService.recordBulkFromJwt` a partir do `AdminController.listAllUsers`.
>
> **Decisão de modelagem — uma entrada por titular retornado, não uma agregada por requisição.**
> O `AuditLog` tem `targetUserId` singular e o índice `(targetUserId, timestamp desc)` serve
> `GET /v1/admin/users/{id}/audit-logs`; uma entrada agregada (`targetUserId=null`) registraria o
> evento mas **não apareceria no histórico de titular algum** — ou seja, registraria a leitura sem
> responder "quem acessou o *meu* dado?", que é a pergunta que a trilha existe para responder.
> Guarda automatizada: `AuditLogIntegrationTest.listagemAdministrativa_deveAparecerNoHistoricoDeCadaTitular`.
>
> **Dívida assumida no fechamento:** volume. Uma página no teto (`MAX_AUDIT_PAGE_SIZE=100`) grava
> 100 entradas — num único `insert` em lote, mas numa coleção que segue **sem TTL** (dívida do
> ADR-011, agora mais cara). Se a coleção crescer demais, a saída é TTL/arquivamento, **não**
> voltar à entrada agregada. Os filtros aplicados (`active`/`name`/`email`) **não** são
> registrados: exigiriam campo novo no schema, e os titulares alcançados já são o dado forense
> relevante.

| Gap | Severidade | Cenário de exploração | Caminho de correção |
| --- | --- | --- | --- |
| **G5 — `/v1/admin/**` sem 2FA nem tier dedicado** | MÉDIO | As rotas admin caem no tier MED por-usuário genérico; não há step-up auth/2FA nem rate-limit mais restritivo para mutações destrutivas (`DELETE /v1/admin/users/del/{id}` hard-delete). Combinado com a ausência de revogação ativa de token, o blast radius de um token ADMIN comprometido é alto. | 2FA/step-up para ADMIN e/ou tier dedicado para deletes |
| **G4 — CORS pattern curinga (risco operacional)** | BAIXO | `CORSConfig` usa `setAllowedOriginPatterns(allowedOrigins)` + `allowCredentials(true)`. Seguro hoje (default `localhost:5173`, não-wildcard), mas como vem de `CORS_ALLOWED_ORIGINS`, um pattern curinga setado por engano em prod vira exfiltração cross-origin **com credenciais**. | Validar/rejeitar pattern curinga quando `allowCredentials=true` |
| **G8 — Sem invalidação de sessões concorrentes** | BAIXO | Não há limite/registro de sessões simultâneas; um usuário pode manter N sessões ativas e o logout encerra só a corrente. Combinado com a ausência de revogação ativa, sessões antigas não são revogáveis centralmente. | Limitar/registrar sessões concorrentes (Spring Session) |
| **R-09 — Rede flat Docker (dívida aceita, ADR-019)** | BAIXO | Um container hostil na mesma rede Docker (`user-service-net`) alcança `gateway:8081` diretamente e pode forjar `X-Forwarded-*`, independentemente de `trusted-proxies` (que só protege contra peers externos) e do G10. Resíduo pré-existente — ADR-010 não cobre a rede interna. Mitigação atual: o modelo de ameaça assume que todos os containers da rede são do projeto. | Rede Docker isolada por serviço (network segmentation) em prod |

> **G12 — `OAUTH_CLIENT_SECRET` servido publicamente pelo Swagger (ALTO): fechado (2026-08-04, [ADR-020](adr/ADR-020-swagger-atras-da-sessao.md)).**
> `springdoc.swagger-ui.oauth.client-secret: ${OAUTH_CLIENT_SECRET}` no `gateway.yml` fazia o
> springdoc materializar um `ui.initOAuth({... "clientSecret":"…"})` **literal** dentro de
> `/swagger-ui/swagger-initializer.js` — recurso estático servido a qualquer anônimo enquanto a rota
> foi pública. O segredo publicado era **idêntico** ao `secrets/OAUTH_CLIENT_SECRET`, ou seja, o do
> cliente **confidential** do BFF: sua confidencialidade deixou de existir, restando só a lista de
> `redirect_uri` e o `requireProofKey(true)` segurando a porta. **Não aparece** em
> `/v3/api-docs/swagger-config` — só a leitura do `swagger-initializer.js` revela.
> **Fechamento:** (a) bloco `springdoc.swagger-ui.oauth` removido inteiro — era supérfluo, o
> "Try it out" autentica pela sessão do BFF (cookie `SESSION` + `tokenRelay()`); (b) segredo
> **rotacionado** com re-seed direcionado do `gateway-client` e limpeza das sessões no Redis;
> (c) `/swagger-ui/**` e `/v3/api-docs/**` saíram do `permitAll()` (ver G11).
> **Lição para não repetir:** o risco de uma rota pública não é só o que você pretendeu publicar
> nela — é o que qualquer configuração futura empurrar para dentro dela. O `ServedConfigSecretLeakTest`
> (módulo config-server) é a guarda automatizada; ele falha se qualquer property `springdoc.*` de
> qualquer YAML servido carregar `secret`/`password`/`token`.

> **G11 — `/swagger-ui/*` e `/v3/api-docs/*` públicos: fechado (2026-08-04, [ADR-020](adr/ADR-020-swagger-atras-da-sessao.md)).**
> Era dívida aceita por indisponibilidade do Cloudflare Access (Zero Trust exige cartão de crédito,
> mesmo no free — decisão do operador é não cadastrar). Fechado **sem** o Access: os três matchers
> saíram do `permitAll()` do `SecurityConfig` do gateway e passaram a exigir a sessão OAuth2 do
> próprio BFF. O entry point virou `DelegatingServerAuthenticationEntryPoint` — 302 para
> `/oauth2/authorization/gateway-client` **só** em `/swagger-ui/**` (navegação de browser; um 401
> seco não daria caminho para autenticar), 401 no resto, inclusive `/v3/api-docs/**` (é XHR — um 302
> para HTML faria o `swagger-client` parsear a tela de login como JSON).
> **Não regredir:** devolver esses paths ao `permitAll()` reabre G11 **e** o vetor do G12.
> Escolhido sobre `htpasswd` no nginx para não introduzir mais um segredo estático compartilhado.
> **Consequência do fechamento:** a CSP relaxada do `location /swagger-ui` (com `'unsafe-inline'` em
> `script-src`, registrada em G3) deixou de valer para rota pública.

> **G10 — Portas do host publicadas na base do compose: fechado (2026-08-03, [ADR-019](adr/ADR-019-correcao-elos-login-hostname-unico.md)).** Os dois blocos `ports:` (`8081:8081` do gateway e `${WEB_HOST_PORT:-5173}:80` da interface) foram movidos para `docker-compose.override.yml`. A base prod-safe não publica mais nenhuma porta de aplicação no host — a premissa do ADR-010 é agora verdadeira na topologia base. G10 era pré-requisito do `trusted-proxies` (os dois controles são interdependentes: RFC1918 amplo seria inseguro com as portas abertas). **Dívida residual aceita:** rede flat Docker (R-09, adicionada na tabela abaixo).

> **Gap de asseguramento da topologia de hostname único: fechado (2026-08-06, [ADR-023](adr/ADR-023-smoke-test-automatizado-login-hostname-unico.md)).**
> Não era vulnerabilidade, e sim a **ausência de barreira**: os quatro elos do ADR-019 (Elo 2
> `trusted-proxies`, Elo 3 `/login` e `/default-ui.css` no nginx, Elo 6 XFF reescrevendo
> `remoteAddress`, BUG-001 CSRF do gateway barrando `POST /login`) foram **todos** descobertos ao
> vivo, em produção — os controles ADR-010/018/019 só tinham verificação manual no browser, depois
> do deploy. Nenhuma camada de teste exercitava a cadeia: unitários e `@WebMvcTest` batem no
> controller, os Testcontainers nunca sobem o container `interface`, o `compose-validate` valida
> sintaxe YAML, e o dev local vai direto a `localhost:8082`.
> **Fechamento:** `infra/smoke-test/login-topology-smoke-test.sh` + job `smoke-test-login` no CI —
> sobe a topologia de deploy (menos o `cloudflared`) e valida 5 asserções HTTP contra ela, a cada
> push/PR. A mitigação originalmente proposta (checklist manual de smoke-test) foi **rejeitada**
> por repetir o antipadrão que causou o Elo 1: depender de o operador lembrar de rodar algo — o
> mesmo motivo pelo qual o `assert-env` existe como invariante forçada em vez de comentário.
> **Escopo residual, de propósito:** R-09 (rede flat) e os CSRF latentes BUG-002/BUG-004/BUG-005
> continuam fora — são riscos de outra natureza, não bugs de roteamento pegáveis por asserção HTTP
> contra a topologia normal. Detalhes das asserções em
> [docs/TESTES.md](TESTES.md#smoke-test-da-topologia-de-login-adr-023).

**G9 — Scan transitivo de dependências pendente.** As versões diretas (Spring Boot 4.0.3 /
Spring Cloud 2025.1.0 / Java 21) não têm CVE conhecida no patch level, mas o scan **transitivo**
(Nimbus JOSE, Jackson, BCrypt) ainda não foi rodado nesta auditoria. Rodar `/security-scan` +
OWASP dependency-check (via `dependency-steward`) antes de prod.

**Investigados e sem gap (não re-auditar).** A mesma auditoria descartou dois vetores: **NoSQL
injection** (G6 — `AdminService` usa `Criteria`/`Pattern.quote`, sem concatenação de string;
repositórios com métodos derivados parametrizados) e **timing attack no token de verificação de
e-mail** (G7 — a comparação ocorre sobre o **hash** SHA-256 no índice do Mongo, com token de 256
bits de entropia; o `X-Internal-Token`, esse sim, usa `MessageDigest.isEqual` constant-time).

## Estado atual do deploy (borda Cloudflare)

O deploy é na **própria máquina**, exposto via **Cloudflare Tunnel**. O estado atual é o
**named tunnel com domínio fixo** (overlay `docker-compose.deploy.yml`) — o quick tunnel efêmero
(`*.trycloudflare.com`), que validava a mecânica de borda mas não cruzava a barra de deploy
legítimo, foi substituído.

**Topologia — hostname único.** O túnel entrega em `interface:80` (o nginx do SPA), **não** no
gateway. O nginx faz proxy same-origin de `/v1/users`, `/v1/admin`, `/oauth2`, `/login` (inclui
`/login?error` e `/login/oauth2/**`), `/default-ui.css`, `/logout`, `/connect` e `/swagger-ui`
ao gateway (ADR-019 — `/login/oauth2` substituído por `/login` que subsume todos os subpaths):

```
Cloudflare (TLS) → cloudflared → interface:80 (nginx) → gateway:8081 → serviços internos
```

- **O que isso entrega:** TLS de borda **real e estável** (a Cloudflare termina TLS; o tráfego
  interno segue HTTP). Cookies `Secure` (`APP_COOKIE_SECURE=true`),
  `SERVER_FORWARD_HEADERS_STRATEGY=framework` e o IP confiável `CF-Connecting-IP` (ADR-010)
  alimentando lockout e rate limit. Com a origem **fixa**, os redirect URIs semeados no Postgres
  casam com a URL real e o **fluxo OAuth2/BFF fecha ponta a ponta**.
- **CORS deixa de ser exercitado na prática:** browser e API na mesma origem. Os cookies `SESSION`,
  `XSRF-TOKEN` e `AUTHSESSION` ficam todos escopados no mesmo host, com `SameSite=Lax` natural.
  A allowlist de CORS continua configurada como defesa contra erro de configuração futura.
- **Superfície pública mínima:** nem o gateway nem o authorization-server são alcançáveis
  diretamente. O front-channel do RP-Initiated Logout passou a existir na borda via rota
  `/connect/**` no gateway ([ADR-018](adr/ADR-018-rota-logout-front-channel-borda.md)).
- **Roteamento da borda versionado:** o túnel é **locally-managed** — criado por
  `cloudflared tunnel create` (CLI), autenticado pelo credentials-file JSON
  (`CLOUDFLARE_TUNNEL_CREDENTIALS`) e roteado por `infra/cloudflared/config.yml`, no repositório.
  O `config.yml` traz só a regra catch-all para `interface:80`, sem `hostname:` e sem o ID do
  túnel: o domínio real não entra no repo (**política de sigilo**: valores concretos vivem só no `.env`), e só alcança o túnel o hostname
  cujo `CNAME` aponta para ele. `TUNNEL_ID` e `PUBLIC_ORIGIN` vivem no `.env`.
- **Observabilidade fora da borda pública:** Grafana, Prometheus e Zipkin **não têm regra de
  ingress** no túnel — `${PUBLIC_ORIGIN}/grafana` cai no `try_files` do SPA, não no Grafana, e o
  nginx não faz proxy de nenhum path de observabilidade. No deploy, os três são publicados **só no
  loopback** — Grafana em `127.0.0.1:3000`, Prometheus em `127.0.0.1:9090`, Zipkin em
  `127.0.0.1:9411` — acesso do operador na própria máquina do deploy e de mais nenhum host.
  Decisão e alternativas descartadas em `.claude/memory/decisions.md`.
  **Fato a não redescobrir:** o `cloudflared` **não interpola variáveis de ambiente** no `config.yml`
  — uma regra `hostname: ${VAR}` vira hostname literal e nunca casa, caindo no catch-all. Expor algo
  por hostname próprio exigiria o domínio literal no repo (contra a política de sigilo) ou um
  init-container que renderize o config a partir de um template.
- **Swagger atrás da sessão do BFF** ([ADR-020](adr/ADR-020-swagger-atras-da-sessao.md)): o
  Cloudflare Access segue indisponível (Zero Trust exige cartão), mas `/swagger-ui/*` e
  `/v3/api-docs/*` **deixaram de ser públicos** — saíram do `permitAll()` e exigem a sessão OAuth2
  do próprio gateway. Anônimo recebe 302 para o login em `/swagger-ui/**` e 401 em `/v3/api-docs/**`.
  Foi durante essa mudança que se descobriu o vazamento do client secret pelo `initOAuth` (G12).

**Invariante de confiança (não regredir):** `CF-Connecting-IP` e o HTTP interno só são seguros
porque **apenas o `cloudflared` alcança** o gateway/auth na topologia base. Expor um serviço direto
reintroduz spoofing de IP e exige TLS interno. **Esta premissa é verdadeira na topologia base:**
os `ports:` do gateway (`8081:8081`) e da interface (`${WEB_HOST_PORT}:80`) foram movidos para
`docker-compose.override.yml` pelo G10 (ADR-019, 2026-08-03) — a base prod-safe não publica
essas portas no host. **Não regredir:** mover os `ports:` de volta para `docker-compose.yml`
reintroduziria spoofing de `CF-Connecting-IP` e neutralizaria rate limit + lockout.

**Manobra de re-seed que não se repete.** A migração de domínio exigiu `docker compose down -v`: o
seed do `gateway-client` em `OAuth2ClientConfig` é idempotente **sem reconciliação**
(`findByClientId` → `save` só se ausente), então mudar `OAUTH_CLIENT_REDIRECT_URIS` com o client já
persistido não atualiza nada. Foi aceitável porque não havia dados reais e os segredos precisavam
ser rotacionados de qualquer forma. **A partir do momento em que houver dados reais, essa manobra
está proibida** — troca de domínio passa a exigir `UPDATE` direcionado no Postgres ou um seed
reconciliador.

## LGPD — proteção de dados pessoais

Guardar nome, e-mail e hash de senha de pessoas reais torna o operador **controlador de dados
pessoais** sob a LGPD. Esta seção rastreia a postura do sistema frente aos deveres da lei — os
controles já implementados e o que ainda falta.

| Direito / dever LGPD | Estado | Onde / o que falta |
| --- | --- | --- |
| **Base legal / consentimento** | ✅ Implementado | `termsAccepted` obrigatório (`true`) no cadastro → `consentAcceptedAt` + `termsVersion` na coleção `users` (ADR-012). Aceite versionado permite reconsentimento quando os termos mudarem. |
| **Trilha de auditoria de acesso** | ✅ Implementado | Coleção `auditLogs` registra *quem acessou/alterou/apagou qual dado de qual titular, quando* (ADR-011) — distinta do log SLF4J operacional. Ver "Controles ativos". |
| **Controle de acesso a dado de terceiro** | ✅ Implementado | A leitura de PII por id/e-mail é **ADMIN-only** (`GET /v1/admin/users/{id}` e `.../email/{email}`, ADR-016 — fecha o G1); um `USER` só lê o próprio dado (`/me`). Acesso admin a PII é auditado (`ADMIN_READ_USER`). |
| **Eliminação / direito ao esquecimento** | ✅ Implementado | Self-service: soft-delete (`/remove/me`) e hard-delete (`/delete/me`) da própria conta. A via ADMIN (soft/hard-delete de outro titular) foi removida do `UserController` (ADR-013) e absorvida pelo `AdminController` dedicado (`DELETE /v1/admin/users/{id}` e `.../del/{id}`, ADR-014). |
| **Minimização** | ✅ Implementado | Coleta enxuta (nome, e-mail, senha); PII mascarada em log (`LogUtils.maskEmail`) e em `auditLogs` (`targetEmail` mascarado). |
| **Portabilidade (exportar meus dados)** | ❌ Gap | Falta endpoint para o titular exportar os próprios dados. Pode ser pós-lançamento, mas planejar. |
| **Notificação de incidente** | ⚠️ Parcial | A auditoria (`auditLogs`) e a observabilidade sustentam a investigação; falta **plano** formal de resposta/notificação e alertas (Alertmanager) sobre os SLOs. |

**Dívida da trilha de auditoria (consciente):** escrita assíncrona → risco de perda em crash antes do
flush; **sem retenção/TTL definidos** — dívida que ficou mais cara com o fix do G13, já que uma
listagem no teto de página grava 100 entradas. (A cláusula "a listagem `GET /v1/users` não é
auditada" saiu daqui em 2026-08-05: essa rota não existe desde o ADR-021, e a listagem
administrativa que a substituiu é auditada.) O endpoint de consulta já existe (`GET /v1/admin/audit-logs` e `.../users/{id}/audit-logs`, ADMIN-only,
[ADR-014](adr/ADR-014-admin-controller-gestao-roles-auditoria.md)) — fecha parcialmente a dívida
original. Detalhe e racional em [ADR-011](adr/ADR-011-trilha-auditoria-dado-pessoal.md).

## Como manter este documento

- Ao **fechar** um gap, mova-o de "gaps" para "controles ativos" (ou remova) e registre em
  `.claude/memory/decisions.md`.
- Um achado em **"Gaps recém-identificados (a tratar)"** tem dois destinos: ao ser **corrigido**
  vira "controle ativo"; ao ser **conscientemente aceito** vira linha na tabela de "dívida aceita".
  Não deixe um achado morar na seção "a tratar" indefinidamente — ou se trata, ou se ratifica.
- Ao **introduzir** dívida de segurança consciente, registre-a aqui com mitigação e caminho de
  saída — dívida não documentada é a que volta a morder.
- Mudanças que afetem contrato/superfície de segurança seguem o fluxo com ADR
  (ver [docs/adr/](adr/) e [docs/CONVENCOES.md](CONVENCOES.md)).
