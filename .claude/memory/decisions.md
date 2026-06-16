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
