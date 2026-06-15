# Workflow: Hotfix (correção urgente)

> Variante mínima do bugfix para incidentes que exigem correção imediata. Mantém os
> portões de segurança essenciais — velocidade **não** dispensa regressão nem revisão.

## Gatilho
Incidente ativo em `{service_target}` (falha de produção, regressão crítica, falha de
segurança explorável). Severidade típica: P0/P1.

## Sequência

### FASE 1 — Triagem (`product-manager` mínimo)
Uma frase: qual AC/contrato está quebrado, qual o blast radius (serviços afetados via
`context.json`), e o critério de "resolvido". Registra o incidente em
`.claude/memory/blockers.md`.

### FASE 2 — Correção mínima (`techlead`)
Menor mudança possível que estanca o incidente. Relatório pré-implementação enxuto +
confirmação. **Sem refatoração.** Se exigir mudança de contrato, documenta como dívida
para um follow-up (feature) — não amplia o escopo no hotfix.

### FASE 3 — Regressão (`qa-tester`)
Teste que reproduz o incidente + suíte do módulo afetado. **Portão inegociável:** nenhum
hotfix segue sem regressão verde, mesmo sob urgência.

### FASE 4 — Revisão express (`senso-critico`, reviewing: full)
Foco em Dimensão 3 (bugs latentes) e Dimensão 4 (adversarial: "o fix abre outro buraco?").
Se o incidente é de segurança, o `security-reviewer` revisa em paralelo (`/security-scan`).
- `REJECTED` com BLOQUEADOR → para e escala ao humano imediatamente.
- `APPROVED` → registra a decisão e move o incidente de `blockers.md` para `decisions.md`.

### FASE 5 — Registro + follow-up (`doc-keeper`)
Atualiza docs afetados e registra qualquer dívida deixada para tratar depois
em `.claude/memory/decisions.md`.
