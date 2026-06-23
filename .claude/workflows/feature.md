# Workflow: Nova feature em um serviço

## Gatilho
Tarefa de feature em `{service_target}`. O orquestrador (seção no `CLAUDE.md`) conduz
a sequência, invocando cada agente e relaiando o relatório de um ao próximo via prompt
(não há barramento vivo — o hand-off é o thread principal).

## Sequência

### FASE 1 — Especificação (`product-manager`)
Lê `context.json`; produz spec com impacto no ecossistema, AC-NN (Gherkin), DoD, riscos
de regressão. Marca `api_contract_changed` e `security_surface_touched`.

### FASE 2 — Validação da spec (`senso-critico`, reviewing: product-manager)
- `REJECTED` → reinvocar `product-manager` com os blockers. **Máx. 2 tentativas** →
  escalar ao humano e parar.
- `APPROVED` / `APPROVED_WITH_OBSERVATIONS` → seguir.

### FASE 3 — Implementação (`techlead`)
Lê spec + skills (`java-microservices`, `invariants-and-contracts`,
`inter-service-communication` se aplicável) + `CLAUDE.md`/`docs/CONVENCOES.md`. Relatório
pré-implementação (razão/arquivos a criar/modificar) e **aguarda confirmação**. Cria ADR
(`/new-adr`) se mudar contrato/schema. Escreve código + testes. Roda `/check-compat` e
resolve quebras antes de sinalizar conclusão.

### FASE 4 — Testes (`qa-tester`)
Estratégia por AC-NN; testes unit/controller/integração (usa `/suggest-tests`); regressão.
- Bug **P0** → devolver ao `techlead`, reinvocar `qa-tester` (**máx. 2x**). Se persistir,
  escalar ao humano e parar.

### FASE 5 — Revisão de segurança (`security-reviewer`, **condicional**)
Só quando `security_surface_touched: true` (autenticação, segredos, canal interno,
CSRF/CORS, sessão). Usa `/security-scan`.
- `REJECTED` com BLOQUEADOR → devolve ao `techlead` (máx. 2x) → humano.
- `APPROVED` → seguir.

### FASE 6 — Revisão final (`senso-critico`, reviewing: full)
Lê os relatórios de product-manager + techlead + qa (+ security-reviewer se houve).
Consistência AC↔impl↔testes; compatibilidade (`/check-compat`); bugs latentes; adversarial.
- `REJECTED` → identificar o agente responsável e reinvocá-lo (**máx. 1x**). Se persistir,
  escalar ao humano e parar.
- `APPROVED` → registrar decisões/tech-debt em `.claude/memory/decisions.md`.

## Regra inviolável
Se a feature **altera contrato de API**, a revisão do `senso-critico` é obrigatória nas
FASES 2 e 6, o `techlead` deve ter criado ADR, e `/check-compat` deve fechar sem quebras.
Se toca a **superfície de segurança**, a FASE 5 (`security-reviewer`) é obrigatória.
