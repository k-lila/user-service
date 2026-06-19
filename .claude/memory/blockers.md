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
