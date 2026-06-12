# Skill (referência): Observabilidade

> Documento de conhecimento lido por `techlead` e `senso-critico`. Reflete a stack de
> observabilidade real do projeto.

## Tracing distribuído

- **Zipkin** (`:9411`), propagação **B3**, **100% sampling**.
- Todo log deve carregar `traceId`/`spanId` (injetados automaticamente via B3) para
  correlacionar chamadas entre serviços (ex.: gateway → user-service, auth-server →
  user-service via Feign).
- O gateway propaga `correlationId` (`CorrelationIdFilter`).

## Métricas

- **Micrometer + Prometheus** (`:9090`), endpoint `/actuator/prometheus`, scrape 5s.
- **Grafana** (`:3000`) com dashboards pré-provisionados.
- **SLOs** definidos: 50ms / 100ms / 200ms / 500ms / 1s / 2s.

## Health checks

- `/actuator/health` aberto para healthchecks (config-server e demais). Actuator
  restante é interno (não exposto na borda pública — ver C18).

## Logs estruturados

- SLF4J parametrizado (`{}`), formato em pipe (`| [VERBO] | ação | campo: valor`).
- Campos relevantes: nível, serviço, `traceId`, `spanId`, `correlationId`, mensagem.
- **PII sempre mascarada** com `LogUtils.maskEmail()` — nunca email/nome cru em
  INFO/WARN. Ver `docs/LOGS.md` para a convenção completa.

## Ao revisar/implementar

Todo endpoint ou fluxo novo deve: logar entrada/saída com PII mascarada, preservar a
propagação de `traceId`/`correlationId`, e expor métrica relevante quando for um ponto
de negócio (não só técnico).
