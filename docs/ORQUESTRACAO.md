# Sistema de Orquestração de Agentes

Quando atuo como **orquestrador**, conduzo um time de subagentes especializados
(`.claude/agents/`) sobre este ecossistema de microsserviços. A delegação acontece pelo
thread principal: invoco cada subagente, recebo seu relatório estruturado e relaio o que
importa ao próximo — **não há barramento de mensagens vivo** (subagentes do Claude Code
rodam em contexto isolado e efêmero). O estado que persiste entre tarefas vive em
`.claude/memory/`.

O pipeline é o caminho para qualquer mudança de domínio (feature, bugfix, novo serviço): ele
**habilita a evolução** protegendo as invariantes da v1 (revisão adversarial + ADRs + gate de
cobertura) — os guardrails que deixam o produto crescer sem regredir.

## Agentes

| Agente               | Modelo                               | Responsabilidade                                                                   | Quando invocar                          |
| -------------------- | ------------------------------------ | ---------------------------------------------------------------------------------- | --------------------------------------- |
| `product-manager`    | sonnet-4-6                           | Spec, impacto no ecossistema, critérios de aceite (AC-NN), DoD                     | Sempre primeiro                         |
| `techlead`           | sonnet-4-6 (opus-4-8 p/ arquitetura) | Implementa sem regredir invariantes; cria ADRs                                     | Após spec aprovada                      |
| `qa-tester`          | sonnet-4-6                           | Testes (unit/controller/integração), regressão, bugs P0–P3                         | Após implementação                      |
| `senso-critico`      | opus-4-8                             | Revisão adversarial: consistência + **compatibilidade de contrato** + bugs latentes | Após o `product-manager` e após o `qa-tester` |
| `security-reviewer`  | opus-4-8                             | Revisão de segurança dedicada (authn/authz, segredos, canal interno, CSRF/CORS)    | Condicional (superfície de segurança) + novo serviço |
| `dependency-steward` | sonnet-4-6                           | Higiene de dependências: CVE, upgrades Spring/React, compat de versões             | Higiene de dependências / CVE / upgrade |
| `doc-keeper`         | sonnet-4-6                           | Sincroniza `CLAUDE.md` e `docs/` com o código                                      | Fase final, após `APPROVED`             |

> Histórico: `techlead` fundiu os antigos `backlog-driver` + `security-auditor`, mas a
> revisão de segurança voltou a ser dedicada no **`security-reviewer`** (a evolução ativa
> prioriza não regredir segurança a cada entrega); `senso-critico` absorve o antigo
> `error-analyst` e a
> antiga ideia de um `compat-guardian` (compatibilidade de contrato é sua responsabilidade,
> operada pela skill `/check-compat`).

## Skills

**Invocáveis** (slash commands): `/suggest-tests <Classe>` (gera testes faltantes, alimenta
o `qa-tester`); `/check-compat [base-ref]` (checa quebra de contrato entre serviços,
read-only); `/new-adr "<título>"` (scaffolda ADR do template com o próximo número);
`/security-scan [escopo]` (varre regressões de segurança, alimenta o `security-reviewer`).

**Referência** (conhecimento lido pelos agentes, não invocável): `java-microservices`,
`test-strategy`, `inter-service-communication`, `observability` e `invariants-and-contracts`
(invariantes + superfícies de contrato; base do `/check-compat`).

## Workflows (`.claude/workflows/`)

`feature.md`, `bugfix.md`, `hotfix.md`, `new-service.md`, `dependency-update.md`. O
pipeline de domínio segue `product-manager → senso-critico → techlead → qa-tester →
[security-reviewer] → senso-critico → doc-keeper` com pontos de retry e escalonamento.
Protocolo geral:

```
1. Carregar .claude/memory/context.json → identificar serviço(s) alvo e workflow
2. product-manager → especificação (AC-NN; marca security_surface_touched)
3. senso-critico   → revisa a spec   (REJECTED 2x → escala ao humano, PARA)
4. techlead        → implementa + ADR se mudar contrato/schema + /check-compat
5. qa-tester       → testa           (bug P0 → devolve ao techlead, máx. 2x)
6. security-reviewer → CONDICIONAL: só se a superfície de segurança foi tocada
7. senso-critico   → revisão final   (REJECTED → agente responsável, máx. 1x → humano)
8. APPROVED        → doc-keeper sincroniza docs/ e registra em decisions.md
```

> `dependency-update.md` é o workflow de higiene de dependências do `dependency-steward`:
> steward (bump) → qa-tester (regressão) → security-reviewer (se dep de segurança) → doc-keeper.

## Memória (`.claude/memory/`)

- `context.json` — mapa de serviços + tarefa corrente
- `decisions.md` — log de decisões/tech-debt (ADRs formais ficam em `docs/adr/`)
- `blockers.md` — impedimentos ativos

## Regras invioláveis

- **Nunca** pule o `senso-critico` em tarefas que afetam contrato de API
- **Nunca** pule o `security-reviewer` quando a superfície de segurança é tocada ou em novo serviço
- **Nunca** permita que o `techlead` altere contrato de API sem ADR (`docs/adr/`)
- Mudança de contrato ou de schema **exige ADR**; cobertura mínima **80%** nas classes
  novas/alteradas (70% é o piso bloqueante, **enforçado pelo gate JaCoCo no `mvn verify`/CI**)
- Sempre registre decisões em `decisions.md` e bloqueadores em `blockers.md`
- Após no máximo 2 rodadas de revisão sem aprovação, escale ao humano e pare

## Uso direto (fora do pipeline)

Os agentes também podem ser chamados isoladamente via Claude Code: `techlead` para
"implemente C\<n\>" ou "feche o gap G\<n\>"; `senso-critico` para auditoria preventiva
ou "qual gap fechar agora?"; `security-reviewer` para uma auditoria de segurança;
`dependency-steward` para "audite/atualize as dependências"; `doc-keeper` após mudanças
que afetem `docs/`. Skills: `/suggest-tests <Classe>`, `/check-compat`, `/security-scan`,
`/new-adr "<título>"`.
