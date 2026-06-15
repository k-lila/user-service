# Registro de Decisões (ADRs leves e tech-debt)

> Log append-only de decisões tomadas durante as tarefas e de melhorias/tech-debt
> identificadas pelo `senso-critico`. Decisões arquiteturais formais (mudança de
> contrato de API, schema, padrão de resiliência) vão em `docs/adr/ADR-NNN-*.md`;
> aqui ficam o resumo rastreável e os itens de melhoria que não bloqueiam release.
>
> **Formato de entrada:**
>
> ```
> ## [AAAA-MM-DD] TASK-NNN · {servico} · {título}
> - **Decisão:** o que foi decidido e por quê.
> - **ADR:** docs/adr/ADR-NNN-*.md (se houver).
> - **Tech-debt / melhorias:** itens do senso-critico que não bloquearam.
> - **Tipo:** decisão | melhoria | observação.
> ```

---

<!-- Novas entradas abaixo -->

## [2026-06-13] infra/compose · Limites de CPU/memória por serviço
- **Decisão:** todos os 26 serviços do `docker-compose.yml` ganharam teto/reserva de recursos via as chaves de nível de serviço `cpus`/`mem_limit`/`mem_reservation` (não `deploy.resources`). Razão: `docker compose up` (v2) aplica essas chaves de forma determinística **fora do Swarm**, enquanto parte de `deploy:` só vale em Swarm — buscamos efeito garantido em standalone. Perfis por classe (App JVM pesado 1.0/1024m/512m; App JVM leve 0.75/512m/256m; Mongo 1.0/1024m/512m; PG 0.75/512m/256m; Redis 0.5/256m/64m; Sentinel 0.25/128m; Zipkin/Prometheus 0.5/512m/256m; Grafana 0.5/256m/128m; exporters/nginx 0.25/128m-64m; mongo-init 0.5/256m). Valores são defaults de dev-blueprint (folga para não OOMKillar no boot; JVM 21 calibra heap ~25% via MaxRAMPercentage).
- **ADR:** não — operação/infra, sem mudança de contrato de API ou schema.
- **Verificação:** `docker compose -f docker-compose.yml config` exit 0 (só o warning pré-existente de `version`); config resolvida renderiza 69 chaves de recurso (17 serviços×3 + 9×2); dev (base+override) também exit 0. Valores documentados em `docs/CONFIG.md` (nova seção "Limites de recursos").
- **Tech-debt / observações:** (a) `version: "3.9"` segue obsoleto (warning pré-existente, fora de escopo); (b) limites não exercitados sob carga real — são tetos de contenção, calibrar no consumidor do blueprint.
- **Tipo:** decisão.

## [2026-06-13] user-service + authorization-server · Hardening (login + leitura de ativos + isolamento de config de teste)
- **Decisão:** três ajustes de finalização do blueprint:
  - **Login (auth-server):** `AuthorizationService.loadUserByUsername` deixou de engolir tudo em `RuntimeException("Erro de comunicação interna")`. Agora `UsernameNotFoundException` (fallback do CB / não-encontrado) propaga sem reembrulhar (→ credenciais inválidas, volta ao login em vez de 500), `null` é tratado explicitamente, e só o inesperado vira uma `UsernameNotFoundException` genérica (sem vazar a causa). Alinha o código com o intent do CLAUDE.md ("indisponibilidade retorna UsernameNotFoundException imediatamente").
  - **Leitura de ativos (user-service):** endpoints de leitura passam a expor só `active=true` — `searchAll` usa `findByActiveTrue`; `searchById`/`searchByEmail` tratam inativo como 404. **Mudança de contrato → ADR-001** (`docs/adr/ADR-001-leitura-somente-ativos.md`). Decisão do humano entre filtrar (escolhida) vs. documentar-como-está.
  - **Isolamento de config de teste (user-service):** `AbstractIntegrationTest` ganhou `spring.cloud.config.enabled=false` no `@TestPropertySource`. Sem isso, com a stack Docker no ar o config-server servia a config de Redis Sentinel (hostnames do Compose) ao JVM de teste → `UnknownHostException: redis-sentinel-1` (descoberto ao validar o alinhamento de versão do Spring Boot). Suíte agora determinística com a stack rodando.
- **ADR:** docs/adr/ADR-001-leitura-somente-ativos.md (leitura de ativos).
- **Verificação:** user-service 131→136 testes, auth-server 50→51, ambos verdes com a stack no ar e SEM flag de config (prova o isolamento de config de teste).
- **Tech-debt / observações:** (a) sem endpoint admin para listar inativos (auditoria) — se necessário, novo endpoint admin-only em ADR própria; (b) import duplicado `java.util.List` em `AuthorizationService.java` permanece (fora de escopo).
- **Tipo:** decisão + observação.

## [2026-06-13] · tracing · Fechamento de gaps de tracing distribuído (Zipkin/B3)
- **Decisão:** quatro ajustes na camada de tracing, verificados em runtime (login real + inspeção no Zipkin):
  1. **discovery-server** deixou de declarar `depends_on: zipkin` no `docker-compose.yml` — emitia zero spans (sem dep/config de tracing), o acoplamento só atrasava o boot.
  2. **gateway** ganhou `spring.reactor.context-propagation: auto` (`config/gateway.yml`): sendo WebFlux, o `traceId`/`spanId` no MDC (`logging.pattern.level`) saía vazio sem isso → correlação log↔Zipkin quebrada na borda.
  3. **auth-server → user-service (Feign):** novo `FeignTracingConfig` (`RequestInterceptor`) injeta o contexto B3 corrente no template via `Propagator`. Diagnóstico provou que o executor do circuit breaker **não está no caminho** da chamada (sem thread-hop a corrigir); a instrumentação feign-micrometer registrava o span cliente mas **não emitia os headers B3**, e o user-service abria um trace **órfão**. Confirmado em runtime: auth e user passaram a compartilhar o mesmo traceId.
  4. **gateway `CorrelationIdFilter`:** `X-Correlation-ID` passou a ser semeado do traceId B3 (fallback UUID), alinhando o id de correlação ao trace.
- **ADR:** não — sem mudança de contrato de API ou schema (config de runtime + interceptor de propagação).
- **Tech-debt / melhorias:** (a) `disableTimeLimiter`/executor do CB ficou como hipótese descartada — registrar para não reabrir; (b) sampling `1.0` segue hardcoded nos `*.yml` (custo em prod — externalizar via `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`); (c) Zipkin sem storage backend (in-memory) segue como gap de prod.
- **Tipo:** decisão + observação.

## [2026-06-12] C20 · authorization-server · Config Resilience4j do Feign (`instances.*` → `configs.*`)
- **Decisão:** no `config-server/.../config/authorization-server.yml`, troca de `resilience4j.{circuitbreaker,timelimiter}.instances.user-service` para `configs.user-service`. Com `spring.cloud.openfeign.circuitbreaker.group.enabled=true`, a resolução é `getConfiguration(id) → getConfiguration(group "user-service") → default`, que só enxerga `configs.*`; o bloco `instances.*` ficava inerte e o CB do C7 rodava nos defaults do Resilience4j em produção. Adicionado `minimumNumberOfCalls: 10` (= janela): sem ele, o default 100 impediria o circuito de abrir com janela COUNT_BASED de 10.
- **ADR:** não — config de runtime de resiliência, sem mudança de contrato/schema.
- **Tech-debt / melhorias:** yml de produção não é exercido por testes (config-server só serve o arquivo); o mecanismo segue coberto indiretamente pelo `UserServiceCircuitBreakerIntegrationTest` (yml de teste, já em `configs.*`).
- **Tipo:** decisão.

## [2026-06-13] P1 · observabilidade · Aprovação da spec dos exporters Prometheus (rodada 2/2)
- **Decisão:** spec P1 (exporters Mongo/PG/Redis) APPROVED_WITH_OBSERVATIONS na rodada final. Os 4 achados da rodada 1 foram resolvidos e batem com a topologia real do `docker-compose.yml`:
  - **B1 (failover Redis) resolvido:** AC-05 deixou de supor `redis-1=master`; passou a predicados de quórum falsificáveis (`count(redis_up==1)>=1`, master com `redis_connected_slaves>=1`, role real via `redis_instance_info`). AC-06 novo adiciona os 3 Sentinels como alvos extras no MESMO job multi-target do oliver006/redis_exporter (relabel `__address__`→`__param_target`, exporter em `:9121/scrape`, `instance`=backend real → sem colisão entre data nodes e sentinels). Mecanismo tecnicamente válido. **Sem reincidência → sem escalonamento ao humano.**
  - **C1 resolvido:** AC-03 cobre restart parcial (`up -d mongodb-exporter` com stack no ar); `depends_on` mongo-1/2/3 `service_healthy` + mongo-init `service_completed_successfully` (mongo-init é one-shot `restart: on-failure`, `:197-226` — edge correto).
  - **C2 resolvido:** todos os ACs trocaram "container sobe" por "target UP + métrica-âncora" (`mongodb_up`/`pg_up`/`redis_up`).
  - **M1 resolvido:** AC-02 exige `MONGODB_URI` via env com `authSource=admin` (= `:444`) e `docker inspect` sem credencial em `Cmd`/`Args`.
- **ADR:** não — infra de observabilidade, sem mudança de contrato de API/schema.
- **Crítico (resolver na implementação, não bloqueou a spec):** nomes de métrica do Sentinel em AC-06 estão imprecisos — oliver006/redis_exporter expõe `redis_sentinel_masters`, `redis_sentinel_master_ok_sentinels`, `redis_sentinel_master_ok_slaves`, `redis_sentinel_master_slaves`, `redis_sentinel_known_sentinels` (infixo `master_` + labels por master), não `redis_sentinel_ok_slaves`/`redis_sentinel_known_slaves`. Techlead deve asserir contra a série real.
- **Tech-debt / observações:** (a) fixar o contrato de relabel do redis_exporter explicitamente na spec para o techlead não improvisar layout com colisão de `instance`; (b) confirmar em runtime o label `role` em `redis_instance_info`.
- **Tipo:** decisão + observação.

## [2026-06-13] P1 · observabilidade · Aprovação da implementação dos exporters Prometheus (revisão final)
- **Decisão:** implementação P1 (3 exporters) APPROVED na revisão final pós-techlead. Conferida contra a spec aprovada e contra os arquivos no disco; `docker compose -f docker-compose.yml config` passa (exit 0, só warning pré-existente de `version`), `prometheus.yml` parseia, todos os targets resolvem para serviços reais.
  - AC-01/02/03: imagens pinadas (mongodb_exporter:0.43.1, postgres-exporter:v0.16.0, redis_exporter:v1.62.0).
  - AC-04: credenciais Mongo/PG via env (`MONGODB_URI`/`DATA_SOURCE_NAME`), `command` só com flags — sem credencial em Cmd/Args.
  - AC-05: depends_on com health (mongo-1/2/3 healthy + mongo-init completed; postgres healthy; redis-1/2/3 healthy).
  - AC-06: relabel multi-target correto, `instance`=backend (URI única) → sem colisão data-node vs sentinel; `__address__`→`redis-exporter:9121`.
  - AC-07: job microservices preservado; techlead também corrigiu target órfão `discovery-server:9091` → `discovery-server-1/-2` (Eureka HA real) — correção legítima, sem regressão.
  - AC-08: nenhum exporter publica `ports:` (prod-safe).
- **ADR:** não — infra de observabilidade, sem mudança de contrato/schema.
- **Tech-debt / observações:** (a) `redis-exporter` é SPOF de scrape para os 6 targets redis (multi-target um único container) — aceitável p/ observabilidade, não p/ caminho de dados; (b) `redis-exporter` não declara depends_on dos sentinels (multi-target resolve o alvo no scrape, não na subida) — aceitável; (c) ~~validação em runtime pendente~~ — **concluída**: 13/13 targets UP, `mongodb_up=1`, `pg_up=1`, `count(redis_up==1)=6`, `count(redis_sentinel_masters==1)=3`; AC-03 exercido.
- **Tipo:** decisão + observação.

## [2026-06-13] P1 · observabilidade · Decisões de implementação — seed único e Sentinels no P1
- **Decisão:** duas decisões de design tomadas durante a implementação dos exporters:
  1. **Seed único na URI do `mongodb-exporter`:** a imagem percona/mongodb_exporter força conexão direta quando múltiplos hosts são especificados na URI (`direct connection cannot be made if multiple hosts are specified`). Solução: URI com seed único `mongo-1:27017` + `replicaSet=rs0&authSource=admin` — o driver descobre os demais membros do RS pelo handshake de replica set. Não expõe credencial em `Cmd`/`Args` (passa via variável de ambiente `MONGODB_URI`).
  2. **Sentinels incluídos no P1 (não no P3):** os 3 Sentinels (`redis-sentinel-1/2/3:26379`) foram adicionados como alvos extras no mesmo job multi-target do `redis-exporter` sem custo adicional de container, expondo as métricas `redis_sentinel_*` desde já. Postergar para P3 apenas criaria uma segunda rodada de pipeline sem benefício.
- **ADR:** não — decisões de configuração de container, sem mudança de contrato/schema.
- **Tech-debt / observações:** seed único cria dependência de disponibilidade de `mongo-1` na subida do exporter (mitigado pelo `depends_on: service_healthy`); em caso de falha permanente de `mongo-1`, o exporter não resolveria o RS (edge improvável dado o replica set).
- **Tipo:** decisão.
