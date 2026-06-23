# Workflow: Bugfix (fast-track)

## Gatilho
Bug reportado em `{service_target}`. PM simplificado; sequência mais curta que a feature.

## Sequência

### FASE 1 — Análise (`product-manager` simplificado)
Identifica o **AC quebrado**, o impacto e os serviços afetados. Spec simplificada —
**não** exige DoD completo. Marca `security_surface_touched` se o bug é de segurança.

### FASE 2 — Correção (`techlead`)
Relatório pré-implementação + aguarda confirmação. **Constraint:** a correção não deve
alterar contratos de API (se for inevitável, vira feature e cria ADR). Mantém escopo
mínimo — não refatora código adjacente.

### FASE 3 — Testes (`qa-tester`)
**Obrigatório:** escrever um teste de regressão que **reproduz o bug** antes da correção
(falha sem o fix, passa com o fix). Rodar a suíte do módulo afetado.
- Bug P0 remanescente → devolve ao `techlead` (máx. 2x) → escala ao humano.

### FASE 4 — Revisão (`senso-critico`, reviewing: full, express)
Foco: a correção não introduz novo problema (Dimensão 3 — bugs latentes), não quebra
contrato (`/check-compat`) e a regressão está coberta. Se o bug era de segurança, aciona
o `security-reviewer` (`/security-scan`) antes de aprovar.
- `REJECTED` → agente responsável (máx. 1x) → humano se persistir.
- `APPROVED` → registra em `.claude/memory/decisions.md`.
