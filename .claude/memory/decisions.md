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

## [2026-06-15] gateway + authorization-server · namespace Redis separado por serviço para sessões
- **Decisão:** cada serviço passou a gravar as sessões Spring Session sob um `redisNamespace` próprio — gateway `@EnableRedisWebSession(redisNamespace = "gateway:session")` e auth-server `@EnableRedisHttpSession(redisNamespace = "authserver:session")` — em vez do default comum `spring:session`. Antes, o isolamento dos dois conjuntos de sessões no mesmo Redis dependia só da unicidade dos session ids; agora é explícito por prefixo (`gateway:session:*` / `authserver:session:*`), facilitando operar/segregar cada conjunto. Não é bug ativo — é fechamento de dívida consciente.
- **ADR:** amend do ADR-007 (não criou novo) — registrado o namespace dedicado por serviço e a nota de invalidação de sessões no deploy. Sem mudança de contrato de API.
- **Atenção (nota de release):** trocar o namespace invalida as sessões existentes (gravadas sob `spring:session:*`) — todos os usuários deslogam **uma vez** no deploy que introduz a mudança.
- **Verificação:** `RedisSessionIntegrationTest` ajustado ao novo prefixo no helper `chaveDaSessao` (`authserver:session:sessions:`) — único consumidor do prefixo antigo em código (confirmado por grep). Fluxo BFF ponta a ponta (`GatewayOAuth2FlowIntegrationTest`) e testes de sessão verdes.
- **Tipo:** decisão.

## [2026-06-15] authorization-server · flag Secure parametrizável no cookie AUTHSESSION
- **Decisão:** o `DefaultCookieSerializer` do cookie `AUTHSESSION` passou a honrar `app.cookie.secure` via `setUseSecureCookie` (campo `@Value("${app.cookie.secure:false}")`), espelhando o padrão já existente no gateway (`SESSION`/`XSRF-TOKEN`). Default `false` (dev HTTP puro inalterado); o overlay `docker-compose.tls.yml` liga via `APP_COOKIE_SECURE=true` no bloco `authorization-server`. Fecha a assimetria silenciosa em que, sob TLS, o `SESSION` saía com `Secure` mas o `AUTHSESSION` não.
- **ADR:** amend do ADR-007 (não criou novo) — registrado o `Secure` parametrizável no auth-server, simétrico ao gateway. Sem mudança de contrato de API.
- **Verificação:** novo `AuthsessionSecureCookieIntegrationTest` (contexto com `app.cookie.secure=true`) afirma `Set-Cookie: AUTHSESSION; Secure`; `RedisSessionIntegrationTest`/`OAuth2AuthorizationCodeFlowIntegrationTest` seguem verdes com o default false (dev inalterado). `mvn -f authorization-server/pom.xml test` → 6/6 verdes nesse subconjunto.
- **Tipo:** decisão.

## [2026-06-15] esteira · JaCoCo com gate de cobertura
- **Decisão:** plugado o `jacoco-maven-plugin` (versão herdada do `spring-boot-starter-parent` 4.0.3, sem pin) nos 5 módulos back-end. Sem POM agregador (decisão do humano: manter a estrutura atual, agregador fora do escopo da v1) → bloco replicado por POM.
  - **Gate (falha o build) nos 3 módulos de domínio** (`user-service`, `authorization-server`, `gateway`): execution `check` na fase `verify`, regra `BUNDLE`/`LINE`/`COVEREDRATIO` mínimo **0.70** (piso bloqueante do projeto; 80% segue como meta perseguida via testes, não como gate, para não reprovar por poucos %).
  - **Report-only** em `config-server` e `discovery-server` (só `prepare-agent` + `report`, sem `check`) — código de framework fora do escopo de teste deliberado (ver TESTES.md §Fora de escopo); gate de 70% ali seria artificial.
  - **Exclusões mínimas** (escopo report+check): `**/*Application.class` e `**/dtos/**`. `@Configuration` fica dentro da métrica (coberto pelos `@SpringBootTest` de integração).
  - Sem Failsafe no projeto → `prepare-agent` injeta `argLine` que o Surefire (que roda unit + integração) consome automaticamente.
- **Decisão de escopo (escolha do humano):** (1) por módulo, sem agregador; (2) gate no piso 70% (não 80% estrito); (3) config/discovery report-only.
- **ADR:** não — item de esteira/build, sem mudança de contrato de API ou schema.
- **Verificação:** `mvn -f <módulo>/pom.xml verify` exit 0 nos 5; jacoco 0.8.15 resolveu pelo parent. "All coverage checks have been met" nos 3 de domínio (testes mantidos: user-service 136, auth-server 51, gateway 38). Cobertura de LINE medida (via jacoco.csv, já com exclusões): user-service 98,3%, auth-server 95,4%, gateway 100% — nenhum teste faltante a escrever. config-server 81,8% / discovery-server 33,3% (sem gate). Docs em `docs/TESTES.md` (nova §Cobertura (JaCoCo)).
- **Tech-debt / observações:** (a) duplicação do bloco JaCoCo nos POMs é o custo aceito de não ter agregador — se um POM-pai for criado no futuro, mover para `pluginManagement`; (b) a regra `check` não distingue classe nova/alterada (intent do CLAUDE.md) de classe legada — o gate é por bundle do módulo; o discernimento por classe segue manual/senso-critico; (c) prova de que o gate "morde" (forçar <70% reprovar) não foi exercida destrutivamente — a execution `check` rodou e avaliou a regra (não "skipped"), confirmando que está ativa.
- **Tipo:** decisão.

## [2026-06-15] documentação · ADRs retroativos essenciais
- **Decisão:** formalizados 6 ADRs retroativos para decisões estruturais já implementadas e em produção no blueprint, que existiam sem registro formal (`docs/adr/` só tinha o TEMPLATE + ADR-001). Os ADRs são a fonte canônica; esta entrada só aponta:
  - **ADR-002** — `docs/adr/ADR-002-padrao-bff.md` · Padrão BFF (gateway é o cliente OAuth2; SPA usa sessão por cookie e nunca manuseia JWT).
  - **ADR-003** — `docs/adr/ADR-003-estado-oauth-postgresql.md` · Estado OAuth (client/authorizations/consents) em PostgreSQL via JDBC repositories do SAS (escala horizontal).
  - **ADR-004** — `docs/adr/ADR-004-resiliencia-feign-circuit-breaker.md` · Resiliência da chamada auth→user via Feign (Resilience4j + `UserClientFallbackFactory`). Referencia o refinamento C20 ([2026-06-12], `instances.*`→`configs.*`) sem reescrevê-lo.
  - **ADR-005** — `docs/adr/ADR-005-chave-jwk-persistente.md` · Par RSA fixo com `kid` estável carregado de PEM (em vez de gerar por boot).
  - **ADR-006** — `docs/adr/ADR-006-canal-interno-isolado.md` · Canal exclusivo auth↔user (`/internal/users/email/{email}`) fora do gateway, protegido por `X-Internal-Token`.
  - **ADR-007** — `docs/adr/ADR-007-sessao-redis-cookies-distintos.md` · Sessão server-side no Redis (Spring Session) com cookies distintos por serviço (`SESSION` vs `AUTHSESSION`).
- **Escopo (escolha do humano):** além dos 3 mínimos (BFF/Postgres/Feign), adicionados 3 estruturais (JWK/canal interno/sessão). Índice de ADRs registrado como linha no `CLAUDE.md` §Mapa de documentos (não criado `docs/adr/README.md`).
- **ADR:** as próprias entradas em `docs/adr/` (este é um item de documentação; nenhum código, config, contrato ou schema foi tocado).
- **Verificação:** `docs/adr/` agora tem ADR-001..007 + TEMPLATE; cada ADR segue o cabeçalho do TEMPLATE (Status/Data/Serviço/Tarefa) e as 4 seções; conteúdo conferido contra as fontes de verdade (CLAUDE.md + `OAuth2ClientConfig`/`JWKConfig`/`IUserClient`/`UserClientFallbackFactory`/`GatewayRouter`/`SecurityConfig`/`InternalTokenFilter`/`FeignConfig`).
- **Tech-debt / observações:** nenhuma decisão técnica pré-existente foi reescrita — C20, hardening [2026-06-13] e tracing [2026-06-13] são apenas referenciados pelos ADRs.
- **Tipo:** decisão.

## [2026-06-15] observabilidade · Externalização de sampling + storage do Zipkin
- **Decisão:** fecha os dois tech-debts de tracing anotados na entrada [2026-06-13] · tracing (itens (b) sampling hardcoded e (c) Zipkin in-memory).
  - **Sampling:** `management.tracing.sampling.probability` deixou de ser `1.0` hardcoded nos 3 YAMLs que tracejam (`gateway.yml`, `user-service.yml`, `authorization-server.yml`) → `${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:1.0}`. Dev segue 100%; prod reduz via env (ex.: 0.1).
  - **Zipkin storage:** serviço `zipkin` no `docker-compose.yml` ganhou `environment` parametrizando o storage: `STORAGE_TYPE=${ZIPKIN_STORAGE_TYPE:-mem}` + `ES_HOSTS/ES_USERNAME/ES_PASSWORD` via host vars `ZIPKIN_ES_*`. Default dev = `mem` (in-memory).
- **Decisão de escopo (Opção A, escolhida pelo humano):** NÃO subir um Elasticsearch local (nem overlay opt-in). O base segue leve e prod-safe; apontar o Zipkin para um ES externo e validar persistência após restart é **exercício do consumidor do blueprint** — mesma filosofia dos gaps de prod já aceitos (cert ACME, chave JWK). Trade-offs avaliados (A env-only vs. B env-only+overlay de ES); B fica como adição futura isolada se a prova local for desejada.
- **ADR:** não — config de runtime/observabilidade, sem mudança de contrato de API ou schema.
- **Verificação:** `docker compose -f docker-compose.yml config` exit 0 (só warning de `version`); env do zipkin renderiza `STORAGE_TYPE: mem` + ES vazios. Placeholder de sampling usa a forma `${VAR:default}` idêntica a placeholders já funcionais nos mesmos YAMLs; config servida (container antigo, ainda hardcoded) devolve o default `1.0` esperado. Docs: `docs/CONFIG.md` (§ Observabilidade: linha de sampling + tabela de storage do Zipkin) e `.env.example` (bloco de observabilidade prod).
- **Tech-debt / observações:** (a) prova em runtime da edição de sampling requer rebuild do config-server (não feito para não derrubar o stack no ar) — baixo risco dado o padrão idêntico; (b) overlay de ES (Opção B) permanece como possível adição futura.
- **Tipo:** decisão.

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

## [2026-06-15] esteira · Pipeline de CI + branch protection (v1 fechada)
- **Decisão:** criado `.github/workflows/ci.yml` (GitHub Actions) disparado em `push` na `main` e em `pull_request`, com 3 jobs paralelos:
  1. **`backend`** (matrix por módulo — não há POM-pai agregador): `mvn -B verify` em cada um dos 5 serviços (`config-server`, `discovery-server`, `authorization-server`, `user-service`, `gateway`). O `verify` dispara o gate JaCoCo já plugado. Testcontainers usa o Docker do runner `ubuntu-latest`. `fail-fast: false`.
  2. **`frontend`**: `npm ci` + `npm run coverage` no `login-interface` — Vitest com threshold 80% no `vitest.config.ts`. **Escolhido `npm run coverage` (não o literal `npm test`)** porque `test` é `vitest` em watch mode (travaria o runner) e `coverage` enforça o piso de cobertura, espelhando o gate JaCoCo do back.
  3. **`compose-validate`**: `docker compose -f docker-compose.yml config -q` — valida a topologia base a cada PR, com `.env` dummy via `cp .env.example .env` (compose base não tem vars mandatórias `:?`).
- **Branch protection (8b):** a `main` exige os 7 checks verdes (5 do back + frontend + compose-validate) para merge, `strict: true`. Aplicada via `gh api ... /branches/main/protection`; comando documentado no `README.md`. Os nomes de check só existem após a 1ª run, então a regra é aplicada depois da primeira execução verde.
- **ADR:** não — esteira/documentação, sem mudança de contrato de API ou schema (logo, sem pipeline de agentes).
- **Arquivos:** novo `.github/workflows/ci.yml`; `README.md` (badge + seção "Integração Contínua (CI)"); este registro.
- **Tipo:** decisão.

## [2026-06-16] TASK-P4-REDIS-AUTH · infra Redis · Autenticação Redis/Sentinel com senha uniforme (ADR-008)
- **Decisão:** senha uniforme (`REDIS_PASSWORD`) nos 6 nós Redis — 3 data nodes com `--requirepass`/`--masterauth` e 3 sentinels com `requirepass`/`sentinel auth-pass mymaster` (injetados em runtime em `/tmp/sentinel.conf`, sem segredo no `sentinel.conf` versionado). Clientes Spring autenticam com `spring.data.redis.password` (data nodes) e `spring.data.redis.sentinel.password` (sentinels). `redis-exporter` usa `REDIS_PASSWORD` nos 6 alvos multi-target. `REDIS_PASSWORD` fail-fast no compose (`${REDIS_PASSWORD:?...}`), sem default; `.env.example` documenta com `openssl rand -hex 32`.
- **Alternativas rejeitadas:** senha por tier (data ≠ sentinel) — exporter multi-target suporta apenas uma senha global, múltiplos exporters sem ganho proporcional; ACLs por usuário — dívida identificada como próximo passo para prod (aumenta superfície de configuração, exige refatoração dos clientes Spring).
- **Gaps residuais (dívida aceita):** TLS de transporte Redis ausente (senha trafega em claro no handshake AUTH na rede interna; portas nunca publicadas no compose base); ACLs por usuário ausentes (todos os clientes compartilham a mesma senha).
- **ADR:** docs/adr/ADR-008-autenticacao-redis-sentinel.md
- **Tipo:** decisão arquitetural.

## [2026-06-16] security-reviewer · TASK-P4-REDIS-AUTH · autenticação Redis/Sentinel (APPROVED_WITH_OBSERVATIONS)
- **Veredito:** APPROVED_WITH_OBSERVATIONS. Revisão de segurança da introdução de senha nos 6 nós Redis (3 data + 3 sentinel) + clientes Spring + exporter. Sem bloqueadores nem críticos.
- **Escopo revisado:** `docker-compose.yml` (data nodes/sentinels/exporter/3 serviços Spring), 3 YAMLs do config-server (`spring.data.redis.password` + `.sentinel.password`), `.env.example`, `infra/redis/sentinel.conf` (inalterado, sem segredo), ADR-008. Diffs reais na branch `redis-auth` (uncommitted).
- **Gestão do segredo (OK):** `.env` gitignored e não rastreado; `.env.example` só com placeholder `changeme` + instrução `openssl rand -hex 32`; `sentinel.conf` versionado sem segredo (injeção runtime via `echo >> /tmp/sentinel.conf`, coerente com o padrão copy-to-/tmp já invariante). Healthchecks usam `$$REDIS_PASSWORD` (env do container, resolvido em runtime — não literal no compose, não aparece no `ps` como argumento de comando do healthcheck).
- **Fail-fast (OK):** `${REDIS_PASSWORD:?...}` em todos os 6 nós Redis, nos 3 serviços Spring e no exporter — coerente com o padrão `CONFIG_SERVER_PASSWORD`. Nenhum caminho permite subir Redis sem senha; YAMLs usam `${REDIS_PASSWORD}` sem default e o compose garante presença da var.
- **Não-regressão (OK):** sessão (namespace/cookies distintos), lockout, rate limiting, canal interno X-Internal-Token preservados — a senha é transparente aos controles. Risco operacional: deploy NÃO-atômico (data nodes com senha, clientes sem) causa NOAUTH e derruba sessão/cache/rate-limit; ADR-008 documenta a exigência de janela atômica.
- **Defesa em profundidade:** ADR-008 declara honestamente os gaps residuais — TLS de transporte Redis ausente (senha trafega em claro no handshake AUTH na rede interna; mitigado por portas nunca publicadas, confirmado: override de dev NÃO publica 6379/26379) e ACLs por usuário ausentes (próximo passo). Gaps residuais registrados como dívida consciente.
- **Observações ao doc-keeper:** (1) migrar o gap "Redis/Sentinel sem autenticação" de `docs/SECURITY.md` para controles ativos, anotando os dois gaps residuais (TLS Redis, ACLs); (2) corrigir ADR-008 linha 93: cita `REDIS_EXPORTER_PASSWORD` mas a var nativa usada (e correta no resto do ADR/compose) é `REDIS_PASSWORD` — inconsistência só documental.
- **Observações fora do escopo da task (não bloqueiam, registrar origem):** SESSION_TIMEOUT/`spring.session.timeout` em gateway.yml+auth-server.yml e o `APP_COOKIE_SECURE=true` do auth-server no `docker-compose.tls.yml` vieram junto no diff mas pertencem ao trabalho de sessão/ADR-007, não ao Redis-auth.
- **Tipo:** decisão (revisão de segurança).

## [2026-06-17] Tier 0 RELATORIOA · deploy seguro (máquina própria + Cloudflare Tunnel)
- **Decisão (0.1 JWK):** chave de assinatura JWT removida do versionamento. Gerada fora do repo por `infra/jwk/gen-keys.sh` (PKCS#8 + X.509); `authorization-server/src/main/resources/keys/` no `.gitignore`; chaves dev `git rm --cached` + rotacionadas (fingerprint mudou). CI gera par efêmero antes do `mvn verify` do auth-server. Chave antiga no histórico tratada como **comprometida e inerte** (nenhum ambiente a usa). Escolha: rotacionar+parar de rastrear (não `git filter-repo`, que reescreveria histórico compartilhado).
- **Decisão (0.2/0.4 borda):** overlay `docker-compose.deploy.yml` adiciona `cloudflared` (quick tunnel → gateway:8081), `APP_COOKIE_SECURE=true` e `SERVER_FORWARD_HEADERS_STRATEGY=framework` (gateway+auth), e CORS/URLs front-channel via `${TUNNEL_ORIGIN}`. Quick tunnel **valida** a mecânica de borda; URL efêmera **não** cruza a barra (OAuth2 ponta a ponta exige named tunnel + domínio).
- **Decisão (0.3 secrets):** base `docker-compose.yml` tornado **secrets-native** (escolha do usuário: base-native, não overlay/standalone). Segredos saem do `.env` plano para Docker secrets (`./secrets/`, gitignorado), gerados por `infra/secrets/gen-secrets.sh`. Consumo: Spring via `spring.config.import=configtree:/run/secrets/` (nome do arquivo = placeholder); postgres/mongo via `_FILE` nativo; redis/sentinel/mongo-init/postgres-exporter via `$(cat ...)` em runtime (`$$` no compose); redis-exporter via `--redis.password-file`; grafana via `__FILE`; prometheus via `basic_auth.password_file`.
- **Restrição técnica encontrada:** o base usa `${VAR:?}` (parse-time) e a env do SO vence o configtree → migração limpa exige base-native (overlay aditivo não remove env nem teria precedência). `docker compose config -q` NÃO exige os arquivos de secret (CI `compose-validate` segue verde sem eles); só `up` exige → `gen-secrets.sh` é pré-requisito do dev.
- **Resíduo aceito (0.3):** `mongodb-exporter` (distroless, sem shell/flag de arquivo) continua lendo `MONGO_USER`/`MONGO_PASSWORD` do `.env` (deve casar com `./secrets/MONGO_PASSWORD`). Registrado em `docs/SECURITY.md`.
- **Validação:** `compose config` OK nas 3 topologias (base, base+override, base+deploy). **Boot full-stack dev validado (2026-06-17):** 24 serviços healthy; smoke E2E OK — JWKS expõe `kid: user-service-key` (chave do file secret `jwk_private`), `POST /v1/users/register` via gateway → 201 (Mongo auth pela URI secret), config-server serve config 200 (Basic auth pela senha do configtree).
- **Bugs de runtime do secrets-native (corrigidos no 1º boot):** (1) **perms** — Compose **não-Swarm** bind-monta secrets `file:` PRESERVANDO o modo do host (chaves `uid/gid/mode` do long-syntax são **só Swarm**, ignoradas); `chmod 600`+dono UID-host quebra os consumidores não-root (config/auth/gateway/user-service = `appuser` UID 999; mongo re-exec como `mongodb`; grafana 472) → `gen-secrets.sh` usa **644**. Postgres escapou (lê `_FILE` ainda como root, antes do `gosu`). (2) **mongo-init** — folded scalar `>` com linhas mais indentadas que `mongosh` vira newline literal → bash quebra em comandos soltos (`-u: command not found`); alinhar indentação para dobrar em espaços. (3) **redis-exporter** — `--redis.password-file` espera **JSON** `{target: senha}`, não a senha crua → secret dedicado `redis_exporter_json` (6 alvos → `REDIS_PASSWORD`, gerado pelo `gen-secrets.sh`).
- **Arquivos:** novos `infra/jwk/gen-keys.sh`, `infra/secrets/gen-secrets.sh`, `docker-compose.deploy.yml`; modificados `docker-compose.yml`, `infra/prometheus.yml`, `.env.example`, `.gitignore`, `.github/workflows/ci.yml`, `docs/SECURITY.md`.
- **Pendências de doc — ✅ ATENDIDAS (2026-06-17):** `docs/CONFIG.md` (nova seção "Docker secrets" + JWK_*/deploy overlay), `CLAUDE.md` (pré-requisito `gen-secrets.sh`, base secrets-native, overlay deploy, gaps atualizados, ADR-009 na lista), `docs/RELATORIOA.md` (checklist 0.1 fechado + 0.2/0.3/0.4 parciais + subseção "Estado atual da execução"), `ADR-005` (nota de fechamento do 0.1), `README.md` (passo 0 secrets/JWK + seção 2c deploy). **ADR-009 criado** (base secrets-native — mudança de topologia).
- **Tipo:** decisão (infra/segurança).

## [2026-06-17] RELATORIOA item 1.2 · gateway + auth-server · IP do cliente não-falsificável (ADR-010)
- **Decisão:** fonte de IP do lockout (auth) e rate-limit (gateway) em duas camadas — (1) header confiável `security.trusted-client-ip-header` (default `CF-Connecting-IP`, `TRUSTED_CLIENT_IP_HEADER`), fonte primária não-falsificável pois a Cloudflare o sobrescreve e só o `cloudflared` alcança o serviço; (2) fallback `server.forward-headers-strategy=framework` movido para a **base** do config-server (`gateway.yml`/`authorization-server.yml`), antes só nos overlays TLS/deploy. O `X-Forwarded-For` bruto **deixou de ser lido** (era o vetor: cloudflared faz append → leftmost controlado pelo cliente).
- **Lógica centralizada:** novo `com.users.gateway.util.ClientIpResolver` (estático) compartilhado por `RateLimiterConfig.ipKeyResolver` e `RateLimitLogFilter` (chave que particiona = chave logada). Auth: `ClientIpResolver.currentIp(trustedHeader)` recebe o header via `@Value` no `AuthorizationService` e `LoginAttemptListener` (segue fonte ÚNICA do lockout).
- **Invariante de confiança:** o header confiável só é seguro porque só a borda alcança o gateway/auth (portas internas nunca publicadas); expor um serviço direto reintroduz spoofing. Deploy não-Cloudflare deve esvaziar/trocar `TRUSTED_CLIENT_IP_HEADER` e garantir que a borda **substitua** (não anexe) o XFF.
- **Arquivos:** modificados `RateLimiterConfig.java`, `RateLimitLogFilter.java`, `ClientIpResolver.java` (auth), `AuthorizationService.java`, `LoginAttemptListener.java`, `config/gateway.yml`, `config/authorization-server.yml`; novos `gateway/util/ClientIpResolver.java`, `ADR-010`. Testes: `RateLimiterConfigTest`/`RateLimitLogFilterTest`/`RateLimitIntegrationTest` + novo `ClientIpResolverTest` (gateway) e `ClientIpResolverTest` (auth) atualizado. Docs: CLAUDE.md, SECURITY.md, CONFIG.md, RELATORIOA.md (checklist 1.2 fechado).
- **Validação:** gateway 20 testes + auth 23 testes verdes (`mvn -o test` focado).
- **Tipo:** decisão (segurança/borda).

## [2026-06-17] RELATORIOA item 1.4 · user-service · Trilha de auditoria de dado pessoal LGPD (ADR-011)
- **Decisão:** nova coleção Mongo `auditLogs` (user-service) alimentada por `AuditService`, distinta do log SLF4J operacional. Modelo `AuditLog`: timestamp, action (`AuditAction`), actorType (USER/ADMIN/SYSTEM), actorUserId, actorRoles, targetUserId, targetEmail (mascarado via `LogUtils.maskEmail`), correlationId (traceId B3). Índice composto `(targetUserId, timestamp desc)`.
- **Escopo (mutações + leituras sensíveis), capturado nos controllers** (ator/alvo/ação inequívocos): REGISTER, UPDATE, SOFT_DELETE_ADMIN, HARD_DELETE_ADMIN, SOFT_DELETE_SELF, READ_INTERNAL_CREDENTIAL (ator SYSTEM, canal interno), READ_CROSS_SUBJECT (titular ≠ solicitante em searchById/searchByEmail). `/me` e leitura do próprio dado NÃO auditados; gravação após sucesso.
- **Async + isolado de falha:** `AuditAsyncConfig` (`@EnableAsync` + executor `auditExecutor`, `TaskDecorator` copiando MDC; `CallerRunsPolicy`); `@Async` nos métodos públicos do `AuditService`; erro de persistência logado ERROR e nunca propaga. `correlationId` em vez de `sourceIp` (no user-service o IP seria o do gateway; o IP do cliente vive no log de borda — ADR-010).
- **Dívida aceita:** async = risco de perda em crash antes do flush; listagem (`GET /v1/users`) não auditada; sem endpoint de consulta (lê-se via Mongo direto) nem retenção/TTL.
- **Arquivos:** novos `domain/AuditLog.java`, `domain/AuditAction.java`, `repository/IAuditLogRepository.java`, `config/AuditAsyncConfig.java`, `services/AuditService.java`, `ADR-011`; modificados `UserController.java` (+AuditService, +@AuthenticationPrincipal Jwt em searchById/searchByEmail/removeUser/deleteUser), `InternalUserController.java` (+AuditService). Testes: `AuditServiceTest`, `AuditLogIntegrationTest` (Awaitility p/ async), `UserControllerTest`/`InternalUserControllerTest` (verificações de auditoria). Docs: CLAUDE.md, SERVICOS.md (coleção auditLogs), SECURITY.md, LOGS.md (log operacional ≠ auditoria), RELATORIOA (checklist 1.4 + tabela LGPD).
- **Validação:** user-service `mvn -o verify` — 151 testes verdes, gate JaCoCo OK (classes novas ≥80%).
- **Tipo:** decisão (segurança/LGPD/schema).

## [2026-06-17] RELATORIOA LGPD · user-service + login-interface · Consentimento no cadastro (ADR-012)
- **Decisão:** aceite versionado obrigatório no cadastro. `UserRequestDTO.termsAccepted` (`@NotNull` + `@AssertTrue`, grupo `OnCreate` — exige presente E true só no cadastro; ignorado no update). `User`/coleção `users` ganham `consentAcceptedAt` (Instant) + `termsVersion` (String), **nullable** no entity (compat. com legados anteriores ao campo). `RegisterService` seta ambos no register (`termsVersion` via `app.terms.version`/`TERMS_VERSION`, default `v1` — bump permite re-consentimento). `UserResponseDTO` expõe os dois. Front `RegisterBox`: checkbox (links `/terms`/`/privacy`) que desabilita "Criar conta" até marcar; `RegisterRequest.termsAccepted` no payload.
- **Dívida:** páginas `/terms`/`/privacy` ainda não existem (links apontam p/ rotas a criar); sem fluxo de re-consentimento ao mudar `termsVersion` (âncora pronta).
- **Arquivos:** modificados `dtos/UserRequestDTO.java`, `domain/User.java`, `services/RegisterService.java` (+@Value termsVersion), `dtos/UserResponseDTO.java`, `config/user-service.yml` (app.terms.version), `RegisterBox.tsx`, `api/authClient.ts`; `ADR-012`. Testes: `UserControllerTest` (400 sem/false consentimento, 201 com true; buildRequest seta termsAccepted=true), `RegisterServiceTest` (consentAcceptedAt+termsVersion; constructor +"v1"), `UserFlowIntegrationTest`, `RegisterBox.test.tsx` (checkbox, submit bloqueado, payload). Docs: CLAUDE.md, SERVICOS.md (payload+schema), CONFIG.md (TERMS_VERSION), RELATORIOA (checklist+tabela LGPD).
- **Validação:** user-service `mvn -o verify` 154 testes + gate OK; front `npm run coverage` 40 testes + threshold OK; `tsc --noEmit` limpo.
- **Tipo:** decisão (LGPD/contrato/schema).

## [2026-06-18] TASK-ADMIN-CONTROLLER · user-service · Revisão adversarial da spec do AdminController (rodada 1) — REJECTED
- **Decisão:** spec do AdminController (4 endpoints sob /v1/admin/**: listagem c/ inativos, auditoria por titular + feed geral, PATCH roles) REJEITADA na rodada 1 por 3 bloqueadores (ver BLOCK-002). Review completo em `/home/k-lila/.claude/plans/planeje-a-adi-o-de-pure-lampson-senso-critico-1.md`.
- **ADR:** pendente — a tarefa exigirá novo ADR (AuditAction.ROLE_GRANT/ROLE_REVOKE aditivo + contrato dos 4 endpoints + regra de auto-revogação), referenciando ADR-001 e ADR-011 (já previsto na spec; não redescoberto).
- **Críticos a incorporar como nota/ADR (não bloquearam o handoff por si só):** (C1) eviction de `authByEmail` só afeta o PRÓXIMO token — token de acesso já emitido com roles antigas vive até expirar (janela = TTL do access token, não os 5 min do cache); declarar como dívida aceita em SECURITY.md. (C2) `UserResponseDTO` não expõe `roles` → superfície de gestão de roles fica cega; decidir expor ou assumir. (C3) PATCH /v1/admin/** está sob CSRF do gateway (X-XSRF-TOKEN) — antecipar para testes de integração.
- **Tech-debt / melhorias:** (M1) filtro dinâmico name/email sem índice → collection scan; (M2) feed de auditoria sem teto de `size` → drenagem possível da trilha LGPD.
- **Observações de não-regressão verificadas (OK):** ADR-001 (leitura só-ativos) preservado por isolamento de rota (AC-17); ADR-006 (canal interno) e ADR-007 (cookies/sessão) não tocados (AC-16); `security-reviewer` obrigatório PENDENTE — deve revisar pós-implementação (foco B1/C1/C2/M2).
- **Tipo:** observação (decisão pendente da rodada 2 pós-correção do PM).

## [2026-06-18] TASK-DELETE-ME · user-service · Remoção de rotas admin DELETE + /delete/me (self hard-delete) — APPROVED_WITH_OBSERVATIONS
- **Decisão:** APROVADO na revisão final (senso-critico, full). Removidas do `UserController` as rotas admin `DELETE /v1/users/{id}` (soft-delete ADMIN) e `DELETE /v1/users/del/{id}` (hard-delete ADMIN); adicionado `DELETE /v1/users/delete/me` (USER, self hard-delete) espelhando `/remove/me`. `AuditAction.HARD_DELETE_SELF` aditivo; `SOFT_DELETE_ADMIN`/`HARD_DELETE_ADMIN` mantidos reservados p/ `AdminUserController` futuro.
- **ADR:** docs/adr/ADR-013-remocao-rotas-admin-delete-user-controller.md.
- **Não-regressão verificada (OK):** contrato Feign intacto — `IUserClient` consome só `GET /internal/users/email/{email}`; nenhuma rota removida era usada por gateway/auth-server. `registerService.deleteUser` reaproveitado sem alteração (evicta os 3 caches; lança DomainEntityNotFound→404). Sem mudança de schema/DTO/cache. Testes específicos (sem asserts fracos): 6 casos novos p/ /delete/me (204/404/401/403 + auditoria HARD_DELETE_SELF). `mvn verify` 159 testes verdes, cobertura 99% na classe.
- **Tech-debt / observações documentais (doc-keeper):** (OBS-1) `docs/SECURITY.md:111` diz "hard-delete ADMIN (ADR-001)" — incorreto pós-mudança; agora é hard-delete USER self (ADR-013). (OBS-2, NOVA, não sinalizada antes) `docs/SERVICOS.md:131-132` comentário do schema `action` em auditLogs omite `HARD_DELETE_SELF` (enum e ADR-011 já corrigidos).
- **Pipeline:** pular PM + 1ª passada do senso-critico foi aceitável (escopo do usuário em plan mode, específico e baixo risco); regra inviolável honrada — mudança de contrato público coberta por ADR-013 e revista por esta passada do senso-critico. `security-reviewer` já revisou (segurança tocada): APROVADO, sem pendência. Melhoria step-up auth p/ hard-delete = consistente com postura de `/remove/me`, não bloqueia.
- **Tipo:** decisão (contrato de API / superfície de rotas).

## [2026-06-18] TASK-ADMIN-CONTROLLER · user-service · `AdminController` dedicado — gestão de roles, consulta de auditoria, listagem c/ inativos — APPROVED_WITH_OBSERVATIONS
- **Decisão:** APROVADO na revisão final (senso-critico) após 3 rodadas. Novo `AdminController`
  (`/v1/admin/**`, todo método `@PreAuthorize("hasRole('ADMIN')")`): `GET /v1/admin/users`
  (listagem incl. inativos, filtros `active`/`name`/`email`), `GET /v1/admin/users/{id}/audit-logs`
  + `GET /v1/admin/audit-logs` (consulta paginada da trilha LGPD, teto `MAX_AUDIT_PAGE_SIZE=100`),
  `PATCH /v1/admin/users/{id}/roles` (gestão de roles `USER`/`ADMIN`), `DELETE /v1/admin/users/{id}`
  + `.../del/{id}` (deletes administrativos absorvidos do ADR-013). Nova rota `admin-service` no
  gateway, rate limit **MED** (5 req/s, cap 10) — superfície sensível, poucos operadores esperados.
  Enforcement de `ROLE_ADMIN` é exclusivamente downstream (`@PreAuthorize`); o gateway não ganha
  `hasRole()` (decisão explícita, consistente com o padrão do projeto).
- **ADR:** `docs/adr/ADR-014-admin-controller-gestao-roles-auditoria.md`. Fecha a dívida do ADR-001
  (visão administrativa de inativos), do ADR-011 (endpoint de consulta de auditoria — parcial, TTL
  ainda em aberto) e formaliza o `AdminUserController` previsto pelo ADR-013 (nomeado
  `AdminController`, mesmo papel).
- **Regra de auto-revogação:** `PATCH .../roles` bloqueia o ator de remover `ADMIN` de si mesmo —
  checagem contra o estado **persistido no MongoDB** (não o JWT, que pode estar stale até o TTL do
  cache `authByEmail`) → **409 Conflict** se violada. Payload sem `USER` ou role fora de
  `{USER,ADMIN}` → 400 (sem normalização silenciosa).
- **Schema:** `AuditAction` ganha `ROLE_GRANT`/`ROLE_REVOKE` (aditivo); `SOFT_DELETE_ADMIN`/
  `HARD_DELETE_ADMIN` (reservados pelo ADR-013) passam a ter rota ativa. DTOs novos isolados do
  contrato existente: `AdminUserResponseDTO` (expõe `roles`, só nesta superfície ADMIN-only),
  `AuditLogResponseDTO`, `UpdateRolesRequestDTO`.
- **Blockers resolvidos:** BLOCK-002 (rodada 1, 3 bloqueadores sobre enforcement de role na borda,
  DTO sem `roles`, e ancoragem da auto-revogação no JWT) — todos corrigidos nas rodadas 2/3, ver
  `.claude/memory/blockers.md`.
- **Dívida aceita:** janela do token já emitido (evicção de `authByEmail` só afeta o próximo token;
  TTL do access token, não do cache) — documentada em `docs/SECURITY.md`. Retenção/TTL de
  `auditLogs` continua em aberto (ADR-011, não tocado por esta tarefa). Performance do filtro
  dinâmico (`MongoTemplate`/`Criteria`) sem índice dedicado para `active`+`name`+`email`.
- **Testes:** `AdminServiceTest` (17, unit), `AdminControllerTest` (31, `@WebMvcTest`),
  `AdminFlowIntegrationTest` (6, user-service, Mongo+Redis reais), `GatewayAdminRouteIntegrationTest`
  (5, gateway, CSRF/roteamento/rate-limit). Suíte completa do projeto: 368 testes backend+front
  (antes 312), gate JaCoCo (piso 70%) preservado.
- **Pipeline:** `senso-critico` revisou a spec (rejeitada na rodada 1 — BLOCK-002), aprovou nas
  rodadas 2/3; `security-reviewer` revisou pós-implementação (achado C-1: `docs/SECURITY.md`
  desatualizado, corrigido por este `doc-keeper`).
- **Tipo:** decisão (contrato de API / schema / superfície de rotas / segurança).
