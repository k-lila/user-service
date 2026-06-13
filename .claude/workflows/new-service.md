# Workflow: Novo microsserviço

> Raro neste projeto (ecossistema único e quase completo), mas documentado por ser um
> **blueprint**. O viés padrão é **não** criar serviço novo — só com justificativa forte
> de bounded context.

## Gatilho
Proposta de criar `{service_target}` novo. O `senso-critico` revisa a própria
justificativa antes de qualquer scaffolding.

## Sequência

### FASE 1 — Justificativa (`pm`)
Além da spec normal, responda:
- Por que **não** expandir um serviço existente (user-service / authorization-server)?
- Qual o **bounded context** deste serviço?
- Quais serviços ele vai consumir / ser consumido por? (sempre via REST/Feign — não há
  mensageria)
- Como ele se encaixa na borda (gateway) e no descobrimento (Eureka HA)?

### FASE 2 — Revisão adversarial da justificativa (`senso-critico`, reviewing: pm)
Foco: **isto é premature decomposition?** O custo operacional (mais um módulo Maven,
config no config-server, registro no Eureka, observabilidade) se justifica?
- `REJECTED` → reavaliar com `pm` (máx. 2x) → escalar ao humano.

### FASE 3 — Scaffolding + implementação (`techlead`)
Obrigações adicionais:
- Criar **ADR** justificando a criação (`docs/adr/`).
- Novo módulo Maven seguindo a estrutura de pacotes do projeto
  (`config/controller/services/repositories/domain/dtos/exception/utils`).
- Definir o contrato (OpenAPI/Swagger primeiro, depois código); versionar sob `/v1/`.
- Desde o início: `spring.config.import` do config-server, registro no Eureka
  (`EUREKA_URI`), health check `/actuator/health`, métricas Prometheus, logs estruturados
  com `traceId`/`correlationId`.
- Atualizar o mapa de serviços em `.claude/memory/context.json`.

### FASE 4 — Testes (`qa-tester`)
Pirâmide completa (unit/controller/integração com Testcontainers). Validar que o serviço
**falha graciosamente** quando dependências estão down (circuit breaker nas chamadas Feign).

### FASE 5 — Revisão final (`senso-critico`, reviewing: full)
Foco adicional: acoplamento desnecessário introduzido? Contratos versionados?
Observabilidade completa desde o início? JWT/sessão tratados conforme o padrão BFF?

### FASE 6 — Registro (`doc-keeper`)
Atualiza `docs/SERVICOS.md`, `docs/CONFIG.md` (novas variáveis), `docs/TESTES.md`
e o diagrama de arquitetura no `CLAUDE.md`.
