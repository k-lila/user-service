# Skill (referência): Observabilidade

> Documento de conhecimento lido por `techlead` e `senso-critico`. **Toda implementação nova** deve
> preservar `traceId`/`spanId`/`correlationId` e as métricas/SLOs — é o que mantém a evolução
> observável.
>
> **Fonte de verdade:** [docs/OBSERVABILIDADE.md](../../docs/OBSERVABILIDADE.md). Este arquivo é o
> resumo operacional; o racional de cada alvo, painel e threshold vive lá. Em caso de divergência,
> vale o documento — e corrija este.

## Tracing distribuído

- **Zipkin** (`:9411`), propagação **B3**.
- **Sampling é configurável**, não fixo: default de dev é 100%
  (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`), e em prod deve ser reduzido por env. Storage default
  é `mem` — traces somem no restart.
- Todo log deve carregar `traceId`/`spanId` para correlacionar chamadas entre serviços.
- O gateway propaga `correlationId` (`CorrelationIdFilter`). No edge reativo isso depende de
  `spring.reactor.context-propagation: auto`.
- Chamada Feign precisa de `FeignTracingConfig` para não abrir trace órfão.

## Métricas

- **Micrometer + Prometheus** (`:9090`), endpoint `/actuator/prometheus`, scrape 5s.
- Os quatro apps Spring são coletados por **`dns_sd_configs` na porta de management `8181`**, nunca
  na porta de tráfego — é o que dá um target por réplica e o que preserva o fechamento do G14.
- **Grafana** (`:3000`) com 5 dashboards pré-provisionados.
- **SLOs:** 50ms / 100ms / 200ms / 500ms / 1s / 2s.

> **Regra ao mexer em dashboard:** *threshold que assume o topo da escala é bug de dashboard.* Um
> painel precisa ser correto no **piso mínimo** (1 nó Mongo, 1 Redis, 1 Sentinel), não só no
> `--profile ha`.

## Health checks e exposição

- `/actuator/health` aberto para healthchecks. O restante do actuator vive na porta de management
  **8181**, que o compose **nunca publica** — e essa não-publicação **é** o controle de acesso.
- `"/actuator/**"` tem de continuar no `permitAll()` dos `SecurityConfig`: a chain do contexto pai
  governa também a 8181, e removê-la devolve 401/302 na própria porta de management.
- Grafana, Prometheus e Zipkin são publicados presos ao **loopback** e não têm ingress no túnel.
  Nenhum tem lockout, rate limit ou MFA.

## Logs estruturados

- SLF4J parametrizado (`{}`), formato em pipe (`| [VERBO] | ação | campo: valor`).
- Campos relevantes: nível, serviço, `traceId`, `spanId`, `correlationId`, mensagem.
- **PII sempre mascarada** com `LogUtils.maskEmail()` — nunca email/nome cru em INFO/WARN. Ver
  `docs/LOGS.md` para a convenção completa.

## Ao revisar/implementar

Todo endpoint ou fluxo novo deve: logar entrada/saída com PII mascarada, preservar a propagação de
`traceId`/`correlationId`, e expor métrica relevante quando for um ponto de negócio (não só
técnico).
