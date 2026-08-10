# Impedimentos Ativos

> Bloqueadores que pararam um pipeline e aguardam resolução humana ou de outro agente.
> Registrados pelo `senso-critico` (verdict REJECTED com risco real), pelo `qa-tester`
> (bug P0) ou pelo `security-reviewer` (bloqueador de segurança). Remova a entrada apenas
> quando o impedimento for resolvido — mova o resumo para `decisions.md` se virar decisão.
>
> **Formato de entrada:**
>
> ```
> ## [AAAA-MM-DD] BLOCK-NNN · TASK-NNN · {servico}
> - **Origem:** senso-critico | qa-tester | security-reviewer
> - **Severidade:** BLOQUEADOR (P0) | CRÍTICO (P1)
> - **Agente responsável:** product-manager | techlead | qa-tester | dependency-steward
> - **Referência:** AC-NN / C<n> / G<n> / arquivo:linha
> - **Descrição:** o que está bloqueado e por quê (específico e acionável).
> - **Status:** aberto | escalado-humano | resolvido
> ```

---

> _Nenhum impedimento ativo._

## [2026-06-15] BLOCK-001 · TASK-P4-REDIS-AUTH · infra-redis
- **Origem:** senso-critico (revisão da spec, rodada 1)
- **Severidade:** BLOQUEADOR (P0)
- **Agente responsável:** product-manager (spec)
- **Referência:** docker-compose.yml:276 (B1); sentinel.conf + 3 YAMLs (B2)
- **Descrição:** B1 — `masterauth` deve estar nos 3 data nodes (inclusive redis-1), senão o ex-master não reintegra como réplica pós-failover (PSYNC NOAUTH silencioso). B2 — decisão sobre `requirepass`/`sentinel.password` nos sentinels deve ser fechada na spec; senão `spring.data.redis.sentinel.password` ausente causa NOAUTH lazy em produção (CI não pega, pois testes usam Redis standalone sem auth).
- **Status:** resolvido (spec revisada na rodada 2; decisão: senha uniforme nos 6 nós + sentinel.password nos 3 clientes)

## [2026-06-18] BLOCK-002 · TASK-ADMIN-CONTROLLER · user-service
- **Origem:** senso-critico (revisão da spec, rodada 1)
- **Severidade:** BLOQUEADOR (P0)
- **Agente responsável:** product-manager (spec)
- **Referência:** B1 = AC-13 + gateway/.../config/SecurityConfig.java:73-86; B2 = AC-07/08/09/10 + AuditService.java:54-102 + UserResponseDTO.java; B3 = AC-09
- **Descrição:** B1 — AC-13 atribui o enforcement de ROLE_ADMIN à borda, mas o gateway só faz `.anyExchange().authenticated()` (sem `hasRole`); a única barreira é `@PreAuthorize` no AdminController (user-service). Ambiguidade "gateway/user-service" deixa brecha de escalonamento de privilégio (risco P0). B2 — auditoria GRANT/REVOKE atribuída ao controller, mas o controller recebe `UserResponseDTO` que NÃO expõe roles → não há como decidir a transição; ACs negativos sobre escrita assíncrona (auditoria é `@Async` fire-and-forget) sem método de verificação definido. B3 — auto-revogação ancorada no JWT (roles podem ser stale) em vez do estado persistido; identificador de "self" não fixado; status HTTP em aberto ("400 ou 409").
- **Status:** resolvido (spec corrigida pelo product-manager e aprovada pelo senso-critico nas
  rodadas 2/3 — B1 manteve enforcement só downstream via `@PreAuthorize`, decisão explícita
  documentada em ADR-014; B2 resolvido com `AdminUserResponseDTO` expondo `roles` e
  `RoleUpdateResult` interno carregando `adminGranted`/`adminRevoked`; B3 resolvido fixando a
  checagem de auto-revogação no estado persistido no MongoDB, com status **409 Conflict**)

## [2026-06-19] BLOCK-003 · TASK-NOTIFICATION-SERVICE · notification-service (novo) + user-service + gateway
- **Origem:** senso-critico (revisão adversarial da justificativa + spec, FASE 2 do new-service.md, rodada 1)
- **Severidade:** BLOQUEADOR (P0)
- **Agente responsável:** product-manager (spec) + thread principal (decisões 2 e R1)
- **Referência:** B1 = gateway/.../SecurityConfig.java:73-86 + :62-71 (AC-10/11/12); B2 = AuthorizationService.java:77-79 + AC-06/AC-09 (R1); B3 = decisão 2 (rate-limit por IP) + LoginAttemptService (precedente por-conta)
- **Descrição:**
  B1 (borda) — `verify-email` (GET) e `resend-verification` (POST) são pré-sessão (usuário recém-cadastrado, login bloqueado, sem cookie SESSION/JWT) mas casam a rota `/v1/users/**` SEM estar no allowlist `permitAll` do gateway (deny-by-default → 401 antes do user-service). `POST resend-verification` ainda cai no DEFAULT_CSRF_MATCHER (só /v1/users/register é isento) → 403 sem XSRF-TOKEN. A spec não especifica authn nem CSRF dessas rotas. Exige AC explícito: permitAll + isenção CSRF do POST.
  B2 (UX/produto, R1) — o gate de e-mail já está ATIVO no código (emailVerified mapeado para enabled). Ao AC-01 parar de setar emailVerified=true, todo cadastro novo nasce com login bloqueado (DisabledException). Com SMTP down (AC-09: cadastro 201, outbox FAILED), a conta fica permanentemente inacessível e a única saída é resend manual (provavelmente também falhando). P0 documentado != P0 mitigado. Exige escolher grace-window OU decoupling do gate da disponibilidade de envio + AC de não-regressão "conta criada com SMTP down não fica permanentemente inacessível".
  B3 (segurança/decisão) — rate-limit do resend só por IP permite e-mail bombing de uma vítima via IPs rotativos (risco de reputação SMTP). O projeto já tem precedente de contador por (conta,IP) no LoginAttemptService. Aceitar como dívida por-IP é decisão de produto/segurança do usuário, não do thread principal: ratificar A (dívida documentada em SECURITY.md + security-reviewer) ou B (cap por conta).
- **Críticos (não bloqueiam o handoff, resolver na FASE 3/ADR-015):** C1 — índice TTL Mongo sobre notificationOutbox.expiresAt fecharia R8 quase de graça (vs. dívida autoinfligida). C2 — token em query string (decisão 3) reabre R5 via log/trace: exigir mascaramento do token no path (gateway + user-service + tag Zipkin http.url). C3 — inversão de direção Feign (user-service vira consumidor) exige FeignConfig + X-Internal-Token + FeignTracingConfig (trace órfão B3 reincidente) + circuit breaker/fallback no user-service.
- **Pendência de pipeline:** security-reviewer (FASE 5, OBRIGATÓRIO para novo serviço) ainda NÃO revisou — security_surface_touched: true. Foco: X-Internal-Token do notification-service (R6/AC-12), e-mail bombing (B3), token em URL/log (C2/R5), postura de borda dos endpoints públicos (B1).
- **Não-regressão verificada (OK):** AC-08 (emailVerified=null loga normal) coberto por !Boolean.FALSE.equals(null)=true; ADR-012 (consentimento) preservado em RegisterService.java:60-65; contrato Feign IUserClient/AuthDTO intacto (emailVerified já existe no AuthDTO).
- **Justificativa do novo serviço (FASE 1):** APROVADA — bounded context ortogonal (SMTP != identidade) consistente com a separação rígida do projeto; padrão Feign+X-Internal-Token = ADR-006. Não é decomposição prematura.
- **Status:** resolvido (rodada 2/2 do senso-critico, 2026-06-19 — APPROVED com observações p/ FASE 3; ver decisions.md). B1/B2/B3 fechados: permitAll+CSRF no gateway, grace period 24h null-safe (`registrationDate` adicionado ao `AuthDTO` — mudança de contrato aditiva, exige ADR-015), `ResendRateLimitService` por conta. Críticos C1/C2/C3 encaminhados ao ADR-015. PENDENTE: security-reviewer (FASE 5) antes do merge.)

## [2026-06-21] BLOCK-004 · auditoria-seguranca · user-service
- **Origem:** security-reviewer (auditoria de segurança ad hoc, read-only)
- **Severidade:** BLOQUEADOR (P0) — diretamente explorável por qualquer usuário autenticado; bloqueia a barra de produção
- **Agente responsável:** techlead (correção), via pipeline (product-manager → senso-critico → techlead → qa → security-reviewer)
- **Referência:** G1 / `UserController.java` — `GET /v1/users/{id}` e `GET /v1/users/email/{email}` (`@PreAuthorize`/`hasRole('USER')` sem checagem de titularidade); `UserResponseDTO`
- **Descrição:** IDOR de leitura de PII. As duas rotas exigem só `ROLE_USER` e não validam que o titular consultado é o solicitante — qualquer usuário autenticado itera ids/e-mails e lê PII (nome, e-mail, `registrationDate`, `consentAcceptedAt`, `emailVerified`) de **toda a base** (enumeração/exfiltração, insumo de phishing; impacto LGPD). O código já **audita** como `READ_CROSS_SUBJECT` mas **não bloqueia**. Correção: restringir a ADMIN, ou ao próprio titular, ou reduzir o `UserResponseDTO` público. Detalhe e demais achados (G2–G9) em `docs/SECURITY.md § Gaps recém-identificados`.
- **Status:** RESOLVIDO (2026-08-04, [ADR-021](../../docs/adr/ADR-021-remocao-listagem-publica-usuarios.md)) — **após uma resolução parcial e uma falsa-resolução; ler o histórico abaixo antes de confiar em qualquer status de gap.**
  - **2026-06-21 — parcial ([ADR-016](../../docs/adr/ADR-016-leitura-pii-restrita-admin.md)).** A leitura por id/e-mail saiu do `UserController` e virou ADMIN-only no `AdminController`, auditada como `ADMIN_READ_USER`; `READ_CROSS_SUBJECT` ficou `@Deprecated`. O ADR declarou o G1 fechado. **Não estava:** `GET /v1/users` (`searchAll`, `hasRole('USER')`) continuou devolvendo `Page<UserResponseDTO>` de toda a base ativa, sem auditoria — o mesmo desfecho do G1, obtido com uma requisição paginada em vez de enumerar ids. O ADR enumerou as rotas que conhecia em vez de varrer a superfície do controller.
  - **2026-08-04 (manhã) — falsa-resolução.** Este blocker foi marcado RESOLVIDO com base em `docs/SECURITY.md`, `CLAUDE.md` e nas Consequências do ADR-016 — **três documentos que repetiam a mesma afirmação errada**, sem que o `UserController` fosse aberto. A documentação validou a si mesma.
  - **2026-08-04 (tarde) — fechado de fato (ADR-021).** `GET /v1/users`, `SearchService.searchAll` e `IUserRepository.findByActiveTrue` removidos. Guard de regressão: `UserControllerTest.searchAll_deveRetornar405_quandoRotaRemovida`.
  - **Regra que fica:** o critério de "gap fechado" verifica-se **contra o código**, nunca contra outro documento — e "removi as rotas que listei" não é o mesmo que "fechei a superfície".
  - **Dívida remanescente correlata:** a listagem administrativa (`GET /v1/admin/users`) **não é auditada** e é agora a única superfície que devolve PII de vários titulares (gap G13 em `docs/SECURITY.md`).

## [2026-08-03] BLOCK-005 · TASK-login-hostname-unico · login-interface + gateway + nginx
- **Origem:** senso-critico (revisão adversarial da spec, workflow feature.md FASE 2, rodada 1)
- **Severidade:** BLOQUEADOR (P0)
- **Agente responsável:** product-manager (correção da spec)
- **Referência:** B1 = login-interface/nginx.conf:124-128 + AC-04/AC-07/AC-08; B2 = login-interface/src/routes/router.tsx:11-12 + ProtectedLayout.tsx:11 + RegisterBox.tsx:76 + AC-05/AC-NR-03
- **Descrição:**
  B1 (nginx não encaminha /default-ui.css) — a spec roteia o CSS no gateway (AC-07) e o
  coloca no permitAll (AC-08), mas NÃO adiciona um `location /default-ui.css` no nginx. O
  request cai no `location /` (try_files, nginx.conf:124-128) e recebe index.html; sob
  `X-Content-Type-Options: nosniff` (nginx.conf:26) o browser recusa text/html como CSS →
  form de login do IdP sem estilo. Quebra de caminho feliz não coberta por AC. Exige AC +
  `location /default-ui.css` → gateway:8081.
  B2 (migração /login incompleta no SPA) — a spec troca só useRegister (AC-NR-03) e o render
  em `/` (AC-05), mas NÃO define a disposição da rota React `/login` (router.tsx:12) e deixa
  duas navegações client-side ainda apontando para `/login`: ProtectedLayout.tsx:11
  (`<Navigate to="/login">` quando não-autenticado) e RegisterBox.tsx:76 (botão "Voltar").
  Sob a leitura natural ("devolver /login ao IdP" ⇒ remover a rota SPA), essas duas viram
  navegação para rota inexistente (tela em branco ao acessar /dashboard sem sessão). Exige a
  spec fixar: (a) remover router.tsx:12; (b) repontar ProtectedLayout + RegisterBox "Voltar"
  para `/`; (c) AC cobrindo cada.
- **Críticos (não bloqueiam o handoff, resolver na spec/ADR-019):** C1 — `/error` do
  auth-server (permitAll em SecurityConfig.java:93) cai no try_files sob hostname único;
  fora do caminho feliz, mas é front-channel que retorna o SPA silenciosamente — rotear ou
  aceitar conscientemente no ADR-019. C2 — R-05 (proxy Vite `/login`→:8082) é incorreto: em
  dev manual o front-channel vai direto a :8082 (gateway.yml:62), a regra não é exercitada e
  o alvo :8082 destoa dos demais (:8081) — remover a regra.
- **Não-regressão verificada (OK):** callback `/login/oauth2/code/...` continua alcançável
  (nginx `location /login` cobre por longest-prefix; Spring Cloud Gateway `.path("/login")` é
  exato, não colide); OAUTH_CLIENT_REDIRECT_URIS e oauth2-redirect.html do Swagger intactos
  (seed idempotente, R-07); `/.well-known/**` e `/oauth2/token`/userinfo são back-channel
  (issuer interno) — não precisam de nginx; CSP `form-action 'self'` cobre o POST /login
  same-origin desde que o novo `location /login` não declare add_header próprio.
- **Status:** RESOLVIDO (2026-08-03). Blockers B-A (PUBLIC_HOST) e B-B (user-db no R-00) fechados via decisão do humano: PUBLIC_HOST mantido com asserção de coerência fail-fast (`assert-env` init-container no overlay de deploy) e R-00 corrigido para `user-db`. Verificado na revisão FASE 6 (`full`). Ver decisions.md [2026-08-03] e ADR-019.

## [2026-08-03] BLOCK-005 (rodada 2/2) · TASK-login-hostname-unico · +Blocos 0A/0B
- **Origem:** senso-critico (revisão adversarial da spec rev.3, feature.md FASE 2, rodada 2/2 — REJECTED escala ao humano)
- **Severidade:** BLOQUEADOR (P0)
- **Agente responsável:** product-manager (spec)
- **Rodada 1 (fechado):** AC-19 (nginx `location = /default-ui.css` sem add_header), AC-20/21/22 (migração rota SPA), AC-23 (`/error` como dívida no ADR-019), R-05 removido, AC-14 expandido — todos verificados como fechando os blockers/críticos da rodada 1.
- **Novos blockers (Blocos 0A/0B):**
  B-A (Bloco 0A, ponto 3) — remoção de `PUBLIC_HOST` está fora de escopo e semanticamente
  incompleta. `impacted_services` lista só `.env/.env.example` e `docs/CONFIG.md`, mas
  `PUBLIC_HOST` é referenciado em `README.md:113,152,158,174` (comando operacional
  `cloudflared tunnel route dns <túnel> <PUBLIC_HOST>` — precisa do hostname NU, sem esquema),
  `docs/DOMINIO.md:9,214,224,287,435,448` (runbook untracked, item de checklist do `.env`) e
  `docker-compose.deploy.yml:24-25` (comentário). AC-B0A-03 ("nenhum arquivo de compose ou
  script referencia PUBLIC_HOST") é inverificável no escopo declarado. Como a divergência
  `PUBLIC_HOST(app.)` vs `PUBLIC_ORIGIN(api.)` foi a causa-raiz do Elo 1, deixar o `tunnel
  route dns` apontando a uma variável eliminada REINTRODUZ o NXDOMAIN em prod. Decisão exigida:
  (a) manter `PUBLIC_HOST` com asserção de coerência/fail-fast vs `PUBLIC_ORIGIN`, OU (b)
  removê-la e reescrever o comando para derivar o host nu de `PUBLIC_ORIGIN` (`${PUBLIC_ORIGIN#https://}`)
  — em ambos, incluir README.md + docs/DOMINIO.md + docker-compose.deploy.yml no escopo.
  B-B (R-00, ponto 4) — comando de verificação pré-`down -v` usa `db.getSiblingDB('userdb')`,
  mas o database real é `user-db` (`config-server/.../user-service.yml:19`). Retorna 0 sempre →
  falso conforto antes de operação destrutiva que zera Mongo+Postgres. Corrigir para `user-db`.
- **Críticos:** C-A (ponto 5) — justificativa do `down -v` é factualmente errada: `oauth2_registered_client.redirect_uris`
  é coluna TEXT com valores separados por vírgula (esquema JDBC do SAS), NÃO "JSON array"; o
  UPDATE direcionado é baixo-risco. `down -v` aceitável p/ dev (dado descartável), mas a premissa
  falsa contamina R-07/runbook de prod — corrigir. C-B (ponto 1) — `trusted-proxies` também
  habilita o `ForwardedHeadersFilter` (RFC 7239 `Forwarded`), não só `XForwardedHeadersFilter`
  (verificado no jar SCG 5.0.0: ambos condicionais a `TrustedProxies`); documentar no ADR-019.
- **Verificado OK (não bloqueia):** ponto 2 (AC-B0B-03) — ambos os `ClientIpResolver`
  (gateway/util + authorizationserver/util) leem `CF-Connecting-IP` com precedência e NUNCA
  `X-Forwarded-For`; o header flui nginx→gateway→auth-server intacto; habilitar
  `XForwardedHeadersFilter` só altera o fallback `getRemoteAddr()` (inócuo em prod com CF-Connecting-IP
  sempre presente). Regex RFC1918 correto (faixa 172.16-31 ok). `trusted-proxies` NÃO afeta
  `XForwardedRemoteAddressResolver`/predicados (mecanismo `maxTrustedIndex` separado, não usado).
  Todas as envs do overlay derivam de `${PUBLIC_ORIGIN}` (deploy.yml:92-132) → AC-B0A-04 coerente.
- **Observação (ponto 6 — quarto elo):** o round-trip do cookie `SESSION`/`AUTHSESSION` + `state`/PKCE
  sob HTTPS real, `SameSite=Lax`, `APP_COOKIE_SECURE=true` só é exercitado DEPOIS dos 3 elos verdes;
  nenhum AC o decompõe (só o DoD item 8 o pegaria, tarde). Recomendar verificação explícita:
  `Set-Cookie SESSION` com Secure+Lax no callback e ausência de `authorization_request_not_found`/state mismatch.
- **Status:** RESOLVIDO (2026-08-03, revisão FASE 6 `full` → APPROVED_WITH_OBSERVATIONS). B-A: humano decidiu manter PUBLIC_HOST com asserção de coerência (`assert-env`, `docker-compose.deploy.yml`), README/DOMINIO/deploy.yml no escopo. B-B: R-00 usa `db.getSiblingDB('user-db')`. Críticos C-A (premissa CSV vs JSON array) e C-B (dois filtros SCG) corrigidos; AC-24 (quarto elo) e R-09 (rede flat) incorporados. PENDENTE (não bloqueia merge, mas gate): ACs só observáveis sob HTTPS/proxy reais (AC-01/02/03/04, AC-24, AC-B0A-01/02/04, AC-B0B-02) a confirmar no browser pós-rebuild.


## [2026-08-07] BLOCK-006 · TASK-REVALIDACAO-EMISSAO · authorization-server + gateway
- **Origem:** senso-critico (revisão adversarial da spec, workflow feature.md FASE 2, rodada 1/2)
- **Severidade:** BLOQUEADOR (P0)
- **Agente responsável:** product-manager (correção da spec `.claude/memory/spec-TASK-REVALIDACAO-EMISSAO.md`)
- **Referência:** B-1 = AC-18/AC-19 + gateway/.../config/SecurityConfig.java:132-134 + filter/RevocationWebFilter.java:47,54 + test/.../AbstractGatewayIntegrationTest.java:37; B-2 = AC-25/R-02 + infra/smoke-test/login-topology-smoke-test.sh:354-385; B-3 = AC-15 × AC-16; B-4 = AC-09/R-09 + listeners/LoginAttemptListener.java:44-49
- **Descrição:**
  B-1 (segundo decoder sequestra o resource server) — o gateway não declara `ReactiveJwtDecoder`;
  ele vem da autoconfig `ReactiveOAuth2ResourceServerJwtConfiguration`, que é
  `@ConditionalOnMissingBean(ReactiveJwtDecoder.class)`. Declarar o decoder leniente da correção (5)
  como bean desse tipo DESLIGA a autoconfig e o resource server passa a aceitar bearer JWT
  **expirado**. `RevocationWebFilter` injeta por tipo (ambiguidade) e os testes de integração
  MOCKAM o decoder — nenhum teste atual pegaria. Exigir AC: decoder leniente NÃO resolvível como o
  `ReactiveJwtDecoder` do resource server + teste de que bearer expirado continua 401.
  B-2 (AC-25 não é acionável; R-02 fica aberto) — "asserções REVISITADAS" não é verificável e a
  instrução está fora do Gherkin. Após o fix a asserção (d) (igualdade com `${PUBLIC_ORIGIN}/login`,
  script:381) passa tanto com o fix correto quanto com "invalida toda sessão sempre". Exigir sexta
  asserção de caminho feliz no script: registrar titular (`POST /v1/users/register`, dentro da
  carência do ADR-015) → `POST /login` real → `GET /oauth2/authorize` com aquele `AUTHSESSION` →
  302 cujo `Location` começa com `${PUBLIC_ORIGIN}/login/oauth2/code/gateway-client` e contém
  `code=`. Viável: o script já sobe user-service+mongo-1 (:197-200) e já extrai AUTHSESSION+_csrf (:299-316).
  B-3 (contradição interna sobre o instante do teto) — AC-15 diz que o teto (AC-16) usa o carimbo
  de autenticação com fallback `getCreationTime()`; AC-16 diz que compara `getCreationTime()`. Os
  dois divergem sempre que a sessão nasceu anônima (CSRF + saved request) antes do login, que é o
  caminho normal do BFF. Fixar um.
  B-4 (a re-derivação pode ZERAR o lockout) — R-09 é falso positivo no eixo do incremento
  (`LoginAttemptListener:38-42` só conta `AuthenticationFailureBadCredentialsEvent` do
  `ProviderManager`), mas `:44-49` **zera** o contador em `AuthenticationSuccessEvent`
  (`loginSucceeded`). A correção (3) adiciona listener nesse mesmo evento; se a re-derivação usar
  `AuthenticationManager`, toda visita a `/oauth2/authorize` anula o lockout do ADR-010 sem prova de
  senha. Exigir AC negativo: contador do par (conta, IP) inalterado após N acessos e nenhum evento
  de autenticação publicado.
- **Críticos (não bloqueiam o handoff):** C-1 R-04 sem AC e sem o teto de 5 min do TTL de
  `authByEmail` (CacheConfig.java:48); C-2 AC-18 satisfeito por decoder mockado (R-11 sem rede);
  C-3 nenhum AC exige o filtro exercitado na filter chain real (ordem na chain não é mencionada →
  fix inerte com build verde); C-4 escopo do filtro só por negativas (a chain @Order(1) casa TODO o
  `endpointsMatcher` do SAS); C-5 tipo do atributo de sessão não fixado por AC (precedente ADR-021,
  `SerializationException`); C-6 troca do próprio e-mail passa a deslogar (RegisterService:102-116),
  sem AC; C-7 "teto absoluto" depende do TTL do refresh token, que não é congelado (AC-21 congela só
  grant types; não há `tokenSettings` no repo → defaults do SAS); C-8 sem AC de observabilidade das
  decisões do filtro; C-9 AC-22 não exige as envs no anchor `x-spring-app-env`/`.env.example`.
- **Verificado OK (não re-litigar):** R-08 confirmado JÁ MITIGADO (`authorization-server.yml:173-174`,
  `ignoreExceptions: [feign.FeignException$NotFound]`); todas as referências arquivo:linha da spec
  conferem; contrato Feign `IUserClient`↔`InternalUserController` e claims do JWT inalterados
  (TokenCustomizerConfig intocado, DoD-10); invariante "auth-server não acessa Mongo" preservada;
  chaves de cache e `revoke:user:{userID}` sem mudança; cookies/namespaces de sessão inalterados;
  AC-09 (ignorar `accountNonLocked`) é correto e necessário (`AuthorizationService.java:99`).
- **Pendência de pipeline:** `security-reviewer` (FASE 5) obrigatório — `security_surface_touched: true`.
- **Status:** RESOLVIDO (2026-08-07, FASE 2 rodada 2/2 -> APPROVED_WITH_OBSERVATIONS). B-1 fechado por AC-31+AC-30 (o par; AC-31 sozinho passaria no cenario que ele visa). B-2 por AC-25(f), verificada como implementavel com o CODE_CHALLENGE :65 e a extracao de AUTHSESSION/_csrf :304-306 ja existentes. B-3 por AC-16 reescrito. B-4 por AC-32+AC-38+AC-08. Seis restricoes escritas seguem a FASE 3 e uma pendencia humana (P-01) gateia o merge — ver decisions.md [2026-08-07].
