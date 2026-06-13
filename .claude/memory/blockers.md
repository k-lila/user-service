# Impedimentos Ativos

> Bloqueadores que pararam um pipeline e aguardam resolução humana ou de outro agente.
> Registrados pelo `senso-critico` (verdict REJECTED com risco real) ou pelo `qa-tester`
> (bug P0). Remova a entrada apenas quando o impedimento for resolvido — mova o resumo
> para `decisions.md` se virar decisão.
>
> **Formato de entrada:**
>
> ```
> ## [AAAA-MM-DD] BLOCK-NNN · TASK-NNN · {servico}
> - **Origem:** senso-critico | qa-tester
> - **Severidade:** BLOQUEADOR (P0) | CRÍTICO (P1)
> - **Agente responsável:** pm | techlead | qa-tester
> - **Referência:** AC-NN / C<n> / G<n> / arquivo:linha
> - **Descrição:** o que está bloqueado e por quê (específico e acionável).
> - **Status:** aberto | escalado-humano | resolvido
> ```

---

## [2026-06-13] BLOCK-001 · P1-observabilidade · docker-compose.yml / infra/prometheus.yml
- **Origem:** senso-critico
- **Severidade:** BLOQUEADOR (P0)
- **Agente responsável:** pm
- **Referência:** AC (redis_up=1) + job redis multi-target em infra/prometheus.yml
- **Descrição:** O Redis NÃO é uma topologia estática de 3 nós — é Sentinel-managed
  (`infra/redis/sentinel.conf:3` monitora `mymaster` = `redis-1`; `redis-2`/`redis-3`
  sobem com `--replicaof redis-1`). O plano de scrape multi-target apontando direto para
  `redis-1:6379, redis-2:6379, redis-3:6379` quebra em failover: se `redis-1` cair, o
  Sentinel promove uma réplica, mas o exporter contra `redis-1` reporta `redis_up=0` (ou
  reconexão a um nó morto). O AC que afirma `redis_up == 1` para os 3 alvos vira falso
  após failover — teste incorreto/flaky e ponto cego justamente no evento que se quer
  observar. A spec precisa: (a) decidir scrape por-nó-com-role OU via Sentinel; (b) o AC
  de `redis_up` precisa tolerar nó individual DOWN sem reprovar o conjunto; (c) cobrir
  failover explicitamente (P3 já cita "failover do Sentinel" no Grafana — sem métrica
  coletada não há painel).
- **Status:** aberto (devolvido ao pm — rodada 1 de 2)
