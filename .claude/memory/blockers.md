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
