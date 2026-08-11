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

## Agentes e skills

As definições são os próprios arquivos e já chegam ao contexto por eles — **este documento não as
espelha**, para não divergir em silêncio. Modelo, ferramentas e checklist de cada agente estão no
frontmatter e no corpo de `.claude/agents/<nome>.md`.

| Agente | Quando invocar |
| --- | --- |
| `product-manager` | Sempre primeiro: spec, impacto no ecossistema, critérios de aceite (AC-NN), DoD |
| `senso-critico` | Após o `product-manager` **e** após o `qa-tester` — revisão adversarial, compatibilidade de contrato, bugs latentes |
| `techlead` | Após a spec aprovada — implementa sem regredir invariantes; cria ADRs |
| `qa-tester` | Após a implementação — testes, regressão, bugs P0–P3 |
| `security-reviewer` | **Condicional:** superfície de segurança tocada; **sempre** em novo serviço |
| `dependency-steward` | Higiene de dependências: CVE, upgrade, compat de versões |
| `report-writer` | Sob demanda, fora do pipeline linear |

Skills invocáveis: `/suggest-tests <Classe>`, `/check-compat [base-ref]`, `/security-scan [escopo]`,
`/new-adr "<título>"`. Skills de referência (lidas pelos agentes, não invocáveis): `java-microservices`,
`test-strategy`, `inter-service-communication`, `observability`, `invariants-and-contracts` — esta
última é a base do `/check-compat`. Todas em `.claude/skills/`.

> Histórico das fusões, que não está em nenhum outro lugar: o `techlead` fundiu os antigos
> `backlog-driver` + `security-auditor`, mas a revisão de segurança voltou a ser dedicada no
> `security-reviewer` — a evolução ativa prioriza não regredir segurança a cada entrega. O
> `senso-critico` absorveu o antigo `error-analyst` e a ideia de um `compat-guardian`.
## Workflows (`.claude/workflows/`)

`feature.md`, `bugfix.md`, `hotfix.md`, `new-service.md`, `dependency-update.md`. O
pipeline de domínio segue `product-manager → senso-critico → techlead → qa-tester →
[security-reviewer] → senso-critico` com pontos de retry e escalonamento.
Protocolo geral:

```
1. Carregar .claude/memory/context.json → identificar serviço(s) alvo e workflow
2. product-manager → especificação (AC-NN; marca security_surface_touched)
3. senso-critico   → revisa a spec   (REJECTED 2x → escala ao humano, PARA)
4. techlead        → implementa + ADR se mudar contrato/schema + /check-compat
5. qa-tester       → testa           (bug P0 → devolve ao techlead, máx. 2x)
6. security-reviewer → CONDICIONAL: só se a superfície de segurança foi tocada
7. senso-critico   → revisão final   (REJECTED → agente responsável, máx. 1x → humano)
8. APPROVED        → registra em decisions.md
```

> `dependency-update.md` é o workflow de higiene de dependências do `dependency-steward`:
> steward (bump) → qa-tester (regressão) → security-reviewer (se dep de segurança).

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
`dependency-steward` para "audite/atualize as dependências"; `report-writer` para
"resuma o que mudamos hoje" ou "como está a cobertura de testes do user-service" — gera
relatório de impacto ou de estado de uma fatia, sem aprovar/reprovar nada.

> A sincronização de `CLAUDE.md`/`docs/` após `APPROVED` deixou de ter um agente dedicado
> (`doc-keeper`, removido) — fica a cargo de quem conduz o pipeline (humano ou
> orquestrador) atualizar os docs afetados manualmente quando necessário.
