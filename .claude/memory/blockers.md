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

---

## Histórico (podado em 2026-08-10)

O arquivo acumulava **22,7 KB com sete blocos, todos já resolvidos** — um arquivo chamado
"Impedimentos **Ativos**" sem um único item ativo. BLOCK-001 a BLOCK-006 foram encerrados entre
2026-06-15 e 2026-08-07 e viraram histórico; o que sobreviveu deles está nos ADRs
correspondentes e no `decisions.md`.

**Um item merecia sobreviver, e sobreviveu — em outro lugar.** O BLOCK-004 foi marcado RESOLVIDO
com base em `docs/SECURITY.md`, no `CLAUDE.md` e nas Consequências do ADR-016, enquanto
`GET /v1/users` seguia devolvendo PII de toda a base ativa a qualquer `USER`. Essa lição virou
regra explícita em [docs/SECURITY.md § Como manter este documento](../../docs/SECURITY.md):
**o critério de "fechado" verifica-se contra o código, nunca contra outro documento.**

**Regra ao usar este arquivo:** bloqueador resolvido sai daqui. O valor de um arquivo de
impedimentos é que a lista vazia signifique alguma coisa.
