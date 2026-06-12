# Workflow: Nova feature em um serviço

## Gatilho
Tarefa de feature em `{service_target}`. O orquestrador (seção no `CLAUDE.md`) conduz
a sequência, invocando cada agente e relaiando o relatório de um ao próximo via prompt
(não há barramento vivo — o hand-off é o thread principal).

## Sequência

### FASE 1 — Especificação (`pm`)
Lê `context.json`; produz spec com impacto no ecossistema, AC-NN (Gherkin), DoD, riscos.

### FASE 2 — Validação da spec (`senso-critico`, reviewing: pm)
- `REJECTED` → reinvocar `pm` com os blockers. **Máx. 2 tentativas** → escalar ao
  humano e parar.
- `APPROVED` / `APPROVED_WITH_OBSERVATIONS` → seguir.

### FASE 3 — Implementação (`techlead`)
Lê spec + skills (`java-microservices`, `inter-service-communication` se aplicável) +
status real em `TRABALHO_PENDENTE.md`. Relatório pré-implementação (razão/arquivos a
criar/modificar) e **aguarda confirmação**. Cria ADR se mudar contrato/schema. Escreve
código + testes.

### FASE 4 — Testes (`qa-tester`)
Estratégia por AC-NN; testes unit/controller/integração (usa `/suggest-tests`); regressão.
- Bug **P0** → devolver ao `techlead`, reinvocar `qa-tester` (**máx. 2x**). Se persistir,
  escalar ao humano e parar.

### FASE 5 — Revisão final (`senso-critico`, reviewing: full)
Lê os relatórios de pm + techlead + qa. Consistência AC↔impl↔testes; riscos de
microsserviços; bugs latentes; raciocínio adversarial.
- `REJECTED` → identificar o agente responsável e reinvocá-lo (**máx. 1x**). Se persistir,
  escalar ao humano e parar.
- `APPROVED` → registrar decisões/tech-debt em `.claude/memory/decisions.md`.

### FASE 6 — Registro (`doc-keeper`)
Sincroniza os docs afetados (`SERVICOS.md`, `TESTES.md`, `TRABALHO_PENDENTE.md`,
`GAPS_SEGURANCA.md`, `LOGS.md`) conforme o que o techlead alterou.

## Regra inviolável
Se a feature **altera contrato de API**, a revisão do `senso-critico` é obrigatória nas
FASES 2 e 5, e o `techlead` deve ter criado ADR.
