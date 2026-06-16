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
