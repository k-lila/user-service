# Registro de Decisões (ADRs leves e tech-debt)

> Log de decisões tomadas durante as tarefas e de melhorias/tech-debt identificadas pelo
> `senso-critico`. Decisões arquiteturais formais (mudança de contrato de API, schema, padrão de
> resiliência) vão em `docs/adr/ADR-NNN-*.md`; **aqui fica só o resumo rastreável** e os itens de
> melhoria que não bloquearam release.
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

## Poda de 2026-08-10 — leia antes de acrescentar entrada

Este arquivo chegou a **186 KB em 53 entradas**, das quais **41 (~137 KB) narravam decisões já
formalizadas em ADR** — contrariando a própria convenção acima, que manda deixar aqui só o resumo
rastreável. Oito entradas de `TASK-REVALIDACAO-EMISSAO` somavam 37 KB para o que o ADR-025 cobre em
22 KB, e duas delas tinham título, data e veredito **idênticos**.

O que foi feito:

- As 41 entradas redundantes viraram **linhas do índice abaixo**, apontando ao ADR que é a fonte de
  verdade. Nada de exclusivo se perdeu — o ADR sempre continha mais do que a entrada.
- As entradas **sem ADR correspondente** foram preservadas **verbatim**, na íntegra.
- O tech-debt aberto que vivia só aqui (MELH-SEC-01…04, MELH-06-01, AC-28, M1) migrou para
  [docs/SECURITY.md § Melhorias abertas](../../docs/SECURITY.md) — nenhuma pessoa auditando
  segurança abre este log.

**Regra ao acrescentar:** se a decisão tem ADR, escreva **uma linha** no índice e o resto no ADR. Se
não tem, escreva a entrada completa aqui. Um log de 186 KB não é memória — é sedimento.

---

## Índice de decisões formalizadas em ADR

Cada linha aqui era uma entrada longa. A fonte de verdade é o ADR.

| Data | Decisão | ADR |
| --- | --- | --- |
| 2026-06-15 | Namespace Redis separado por serviço para sessões | [ADR-007](../../docs/adr/ADR-007-sessao-redis-cookies-distintos.md) |
| 2026-06-15 | Flag `Secure` parametrizável no cookie `AUTHSESSION` | [ADR-007](../../docs/adr/ADR-007-sessao-redis-cookies-distintos.md) |
| 2026-06-12 | C20 · Resilience4j do Feign: `instances.*` → `configs.*` (com group, `instances` é inerte) | [ADR-004](../../docs/adr/ADR-004-resiliencia-feign-circuit-breaker.md) |
| 2026-06-13 | Hardening do login + leitura só de ativos + isolamento de config de teste | [ADR-001](../../docs/adr/ADR-001-leitura-somente-ativos.md) |
| 2026-06-16 | TASK-P4-REDIS-AUTH · autenticação Redis/Sentinel com senha uniforme | [ADR-008](../../docs/adr/ADR-008-autenticacao-redis-sentinel.md) |
| 2026-06-16 | `security-reviewer` · veredicto da autenticação Redis/Sentinel (APPROVED_W_OBS) | [ADR-008](../../docs/adr/ADR-008-autenticacao-redis-sentinel.md) |
| 2026-06-17 | IP do cliente não-falsificável (header confiável, XFF bruto deixa de ser lido) | [ADR-010](../../docs/adr/ADR-010-resolucao-ip-cliente-confiavel.md) |
| 2026-06-17 | Trilha de auditoria de dado pessoal LGPD | [ADR-011](../../docs/adr/ADR-011-trilha-auditoria-dado-pessoal.md) |
| 2026-06-17 | Consentimento LGPD no cadastro (`termsAccepted`, `consentAcceptedAt`, `termsVersion`) | [ADR-012](../../docs/adr/ADR-012-consentimento-lgpd-cadastro.md) |
| 2026-06-18 | TASK-ADMIN-CONTROLLER · spec rodada 1 — **REJECTED** pelo `senso-critico` | [ADR-014](../../docs/adr/ADR-014-admin-controller-gestao-roles-auditoria.md) |
| 2026-06-18 | TASK-DELETE-ME · remoção das rotas admin DELETE do `UserController` | [ADR-013](../../docs/adr/ADR-013-remocao-rotas-admin-delete-user-controller.md) |
| 2026-06-18 | TASK-ADMIN-CONTROLLER · `AdminController` dedicado (roles, auditoria, listagem c/ inativos) | [ADR-014](../../docs/adr/ADR-014-admin-controller-gestao-roles-auditoria.md) |
| 2026-06-19 | TASK-NOTIFICATION-SERVICE · resolução do BLOCK-003 (verificação de e-mail) | [ADR-015](../../docs/adr/ADR-015-verificacao-email-cadastro.md) |
| 2026-06-19 | `security-reviewer` · revisão de segurança FASE 5 | [ADR-015](../../docs/adr/ADR-015-verificacao-email-cadastro.md) |
| 2026-06-19 | `senso-critico` · revisão final FASE 6 (APPROVED_W_OBS) | [ADR-015](../../docs/adr/ADR-015-verificacao-email-cadastro.md) |
| 2026-08-04 | Diagnóstico do e-mail de verificação que não saía (sem correção na ocasião) | [ADR-015](../../docs/adr/ADR-015-verificacao-email-cadastro.md) |
| 2026-06-22 | Revogação ativa de token — epoch por usuário no Redis, três camadas de checagem | [ADR-017](../../docs/adr/ADR-017-revogacao-ativa-token.md) |
| 2026-07-28 | Migração para domínio fixo via named tunnel | [ADR-018](../../docs/adr/ADR-018-rota-logout-front-channel-borda.md) |
| 2026-08-03 | Correção dos quatro elos de login sob hostname único | [ADR-019](../../docs/adr/ADR-019-correcao-elos-login-hostname-unico.md) |
| 2026-08-03 | Login sob hostname único — processo e lições (`senso-critico`, revisão `full`) | [ADR-019](../../docs/adr/ADR-019-correcao-elos-login-hostname-unico.md) |
| 2026-08-03 | Túnel locally-managed com ingress rules versionadas | [ADR-019](../../docs/adr/ADR-019-correcao-elos-login-hostname-unico.md) |
| 2026-08-03 | TASK-login-hostname-unico · revisão de segurança | [ADR-019](../../docs/adr/ADR-019-correcao-elos-login-hostname-unico.md) |
| 2026-08-03 | Elo 6 · parecer sobre a Opção C (nginx suprime XFF ao gateway) | [ADR-019](../../docs/adr/ADR-019-correcao-elos-login-hostname-unico.md) |
| 2026-08-04 | G12 (`OAUTH_CLIENT_SECRET` servido publicamente) + G11 fechados | [ADR-020](../../docs/adr/ADR-020-swagger-atras-da-sessao.md) |
| 2026-08-04 | Auditoria doc↔código e remoção da listagem pública de usuários | [ADR-021](../../docs/adr/ADR-021-remocao-listagem-publica-usuarios.md) |
| 2026-08-04 | `security-reviewer` · veredicto da leva (G1 + 2 correlatos) | [ADR-021](../../docs/adr/ADR-021-remocao-listagem-publica-usuarios.md) |
| 2026-08-04 | `senso-critico` · revisão adversarial final da leva (FASE 6) | [ADR-021](../../docs/adr/ADR-021-remocao-listagem-publica-usuarios.md) |
| 2026-08-04 | Correção do efeito colateral no caminho 404 (404 volta a contar no lockout) | [ADR-021](../../docs/adr/ADR-021-remocao-listagem-publica-usuarios.md) |
| 2026-08-06 | Bloco 3: higiene do estado persistente (purga OAuth, retenção de auditoria) | [ADR-022](../../docs/adr/ADR-022-higiene-estado-persistente.md) |
| 2026-08-07 | Elasticidade — piso mínimo e eixos de escala | [ADR-024](../../docs/adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md) |
| 2026-08-07 | TASK-REVALIDACAO-EMISSAO · FASE 2 rodada 2/2 · `senso-critico` (APPROVED_W_OBS) | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-09 | TASK-REVALIDACAO-EMISSAO · FASE 3 · gate verificado pelo thread principal | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-09 | TASK-REVALIDACAO-EMISSAO · FASE 4 · `qa-tester` PASS | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-09 | TASK-REVALIDACAO-EMISSAO · FASE 5 · `security-reviewer` (APPROVED_W_OBS) | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-09 | TASK-REVALIDACAO-EMISSAO · FASE 6 · `senso-critico` (condicionada) | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-09 | TASK-REVALIDACAO-EMISSAO · CRIT-06-01 corrigido | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-09 | TASK-REVALIDACAO-EMISSAO · FASE 6 re-report pós-CRIT-06-01 | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-10 | TASK-REVALIDACAO-EMISSAO · condições de merge fechadas, tarefa encerrada | [ADR-025](../../docs/adr/ADR-025-revalidacao-estado-emissao.md) |
| 2026-08-10 | Troca de senha ou e-mail passa a revogar (fecha o G15) | [ADR-026](../../docs/adr/ADR-026-revogacao-troca-senha-email.md) |

---

## Entradas sem ADR (preservadas na íntegra)

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

## [2026-06-13] tracing · Fechamento de gaps de tracing distribuído (Zipkin/B3)
- **Decisão:** quatro ajustes na camada de tracing, verificados em runtime (login real + inspeção no Zipkin):
  1. **discovery-server** deixou de declarar `depends_on: zipkin` no `docker-compose.yml` — emitia zero spans (sem dep/config de tracing), o acoplamento só atrasava o boot.
  2. **gateway** ganhou `spring.reactor.context-propagation: auto` (`config/gateway.yml`): sendo WebFlux, o `traceId`/`spanId` no MDC (`logging.pattern.level`) saía vazio sem isso → correlação log↔Zipkin quebrada na borda.
  3. **auth-server → user-service (Feign):** novo `FeignTracingConfig` (`RequestInterceptor`) injeta o contexto B3 corrente no template via `Propagator`. Diagnóstico provou que o executor do circuit breaker **não está no caminho** da chamada (sem thread-hop a corrigir); a instrumentação feign-micrometer registrava o span cliente mas **não emitia os headers B3**, e o user-service abria um trace **órfão**. Confirmado em runtime: auth e user passaram a compartilhar o mesmo traceId.
  4. **gateway `CorrelationIdFilter`:** `X-Correlation-ID` passou a ser semeado do traceId B3 (fallback UUID), alinhando o id de correlação ao trace.
- **ADR:** não — sem mudança de contrato de API ou schema (config de runtime + interceptor de propagação).
- **Tech-debt / melhorias:** (a) `disableTimeLimiter`/executor do CB ficou como hipótese descartada — registrar para não reabrir; (b) sampling `1.0` segue hardcoded nos `*.yml` (custo em prod — externalizar via `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`); (c) Zipkin sem storage backend (in-memory) segue como gap de prod.
- **Tipo:** decisão + observação.

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

## [2026-06-17] Tier 0 RELATORIOA · deploy seguro (máquina própria + Cloudflare Tunnel)
- **Decisão (0.1 JWK):** chave de assinatura JWT removida do versionamento. Gerada fora do repo por `infra/jwk/gen-keys.sh` (PKCS#8 + X.509); `authorization-server/src/main/resources/keys/` no `.gitignore`; chaves dev `git rm --cached` + rotacionadas (fingerprint mudou). CI gera par efêmero antes do `mvn verify` do auth-server. Chave antiga no histórico tratada como **comprometida e inerte** (nenhum ambiente a usa). Escolha: rotacionar+parar de rastrear (não `git filter-repo`, que reescreveria histórico compartilhado).
- **Decisão (0.2/0.4 borda):** overlay `docker-compose.deploy.yml` adiciona `cloudflared` (quick tunnel → gateway:8081), `APP_COOKIE_SECURE=true` e `SERVER_FORWARD_HEADERS_STRATEGY=framework` (gateway+auth), e CORS/URLs front-channel via `${TUNNEL_ORIGIN}`. Quick tunnel **valida** a mecânica de borda; URL efêmera **não** cruza a barra (OAuth2 ponta a ponta exige named tunnel + domínio).
- **Decisão (0.3 secrets):** base `docker-compose.yml` tornado **secrets-native** (escolha do usuário: base-native, não overlay/standalone). Segredos saem do `.env` plano para Docker secrets (`./secrets/`, gitignorado), gerados por `infra/secrets/gen-secrets.sh`. Consumo: Spring via `spring.config.import=configtree:/run/secrets/` (nome do arquivo = placeholder); postgres/mongo via `_FILE` nativo; redis/sentinel/mongo-init/postgres-exporter via `$(cat ...)` em runtime (`$$` no compose); redis-exporter via `--redis.password-file`; grafana via `__FILE`; prometheus via `basic_auth.password_file`.
- **Restrição técnica encontrada:** o base usa `${VAR:?}` (parse-time) e a env do SO vence o configtree → migração limpa exige base-native (overlay aditivo não remove env nem teria precedência). `docker compose config -q` NÃO exige os arquivos de secret (CI `compose-validate` segue verde sem eles); só `up` exige → `gen-secrets.sh` é pré-requisito do dev.
- **Resíduo aceito (0.3):** `mongodb-exporter` (distroless, sem shell/flag de arquivo) continua lendo `MONGO_USER`/`MONGO_PASSWORD` do `.env` (deve casar com `./secrets/MONGO_PASSWORD`). Registrado em `docs/SECURITY.md`.
- **Validação:** `compose config` OK nas 3 topologias (base, base+override, base+deploy). **Boot full-stack dev validado (2026-06-17):** 24 serviços healthy; smoke E2E OK — JWKS expõe `kid: user-service-key` (chave do file secret `jwk_private`), `POST /v1/users/register` via gateway → 201 (Mongo auth pela URI secret), config-server serve config 200 (Basic auth pela senha do configtree).
- **Bugs de runtime do secrets-native (corrigidos no 1º boot):** (1) **perms** — Compose **não-Swarm** bind-monta secrets `file:` PRESERVANDO o modo do host (chaves `uid/gid/mode` do long-syntax são **só Swarm**, ignoradas); `chmod 600`+dono UID-host quebra os consumidores não-root (config/auth/gateway/user-service = `appuser` UID 999; mongo re-exec como `mongodb`; grafana 472) → `gen-secrets.sh` usa **644**. Postgres escapou (lê `_FILE` ainda como root, antes do `gosu`). (2) **mongo-init** — folded scalar `>` com linhas mais indentadas que `mongosh` vira newline literal → bash quebra em comandos soltos (`-u: command not found`); alinhar indentação para dobrar em espaços. (3) **redis-exporter** — `--redis.password-file` espera **JSON** `{target: senha}`, não a senha crua → secret dedicado `redis_exporter_json` (6 alvos → `REDIS_PASSWORD`, gerado pelo `gen-secrets.sh`).
- **Arquivos:** novos `infra/jwk/gen-keys.sh`, `infra/secrets/gen-secrets.sh`, `docker-compose.deploy.yml`; modificados `docker-compose.yml`, `infra/prometheus.yml`, `.env.example`, `.gitignore`, `.github/workflows/ci.yml`, `docs/SECURITY.md`.
- **Pendências de doc — ✅ ATENDIDAS (2026-06-17):** `docs/CONFIG.md` (nova seção "Docker secrets" + JWK_*/deploy overlay), `CLAUDE.md` (pré-requisito `gen-secrets.sh`, base secrets-native, overlay deploy, gaps atualizados, ADR-009 na lista), `ADR-005` (nota de fechamento do 0.1), `README.md` (passo 0 secrets/JWK + seção 2c deploy). **ADR-009 criado** (base secrets-native — mudança de topologia).
- **Tipo:** decisão (infra/segurança).

## [2026-06-21] · ecossistema · auditoria de segurança ad hoc (security-reviewer)
- **Decisão:** auditoria preventiva read-only confirmou que os controles ativos e os gaps já registrados em `docs/SECURITY.md` continuam válidos (zero regressão, zero gap fechado silenciosamente) e levantou achados novos não registrados: **G1** IDOR de leitura de PII (qualquer USER lê PII de qualquer titular via `GET /v1/users/{id}` e `/email/{email}` — ALTO), **G2** token válido após conta desativada, **G3** sem headers de segurança HTTP (MÉDIO), **G4** CORS pattern curinga operacional (BAIXO), **G5** `/v1/admin/**` sem 2FA/tier dedicado (MÉDIO), **G8** sem invalidação de sessões concorrentes (BAIXO), **G9** scan transitivo de deps pendente. Investigados e **limpos**: G6 (NoSQL injection — `Criteria`/`Pattern.quote`) e G7 (timing no token de e-mail — hash 256-bit via índice Mongo).
- **Enquadramento (decisão do humano):** os achados novos foram para uma **seção nova "Gaps recém-identificados (a tratar / não ratificados)"** no `SECURITY.md`, **separada** da tabela "dívida aceita" — um IDOR ALTO recém-descoberto não é escolha consciente. G2 foi **consolidado** generalizando o gap de revogação existente (mesma raiz: ausência de revogação ativa de token, cobre role-revoke + conta desativada).
- **Propagação no ecossistema:** sincronizado em `CLAUDE.md` (frase-ponteiro na seção de gaps), skill `/security-scan` (nova Seção 5 varrendo headers + authz/IDOR — fecha a lacuna em que a skill se descrevia varrendo "headers" mas não o fazia), checklist do agente `security-reviewer` (bullets IDOR §1 + headers §3) e `blockers.md` (BLOCK-004 para G1).
- **ADR:** nenhum — registro de postura, sem mudança de contrato/schema. As correções futuras (restringir G1, headers G3 etc.) são tarefas de pipeline e podem exigir ADR próprio.
- **Tipo:** observação + decisão (auditoria ad hoc, fora do pipeline de feature).

## [2026-08-04] Acesso ao Grafana — loopback em vez de exposição pública (opção D1)
- **Pergunta de origem:** o operador perguntou se o Grafana estava acessível online no deploy via Cloudflare Tunnel. **Não estava**, por três barreiras independentes: ingress único do túnel (catch-all → `interface:80`), ausência de `location` de observabilidade no `nginx.conf` do SPA, e ausência de `ports:` para o `grafana` na base prod-safe do compose.
- **Decisão:** **não expor publicamente**. Publicar a porta presa ao loopback (`127.0.0.1:3000:3000`) no `docker-compose.deploy.yml`, dando ao operador acesso em `http://localhost:3000` na máquina do deploy e em lugar nenhum além dela.
- **Fundamentação:** o Grafana é o componente com a autenticação mais fraca do ecossistema — usuário/senha e nada mais. O `LoginAttemptService` (lockout) é do auth-server e o token bucket é do gateway; nenhum dos dois cobre o Grafana, e não há MFA. Expô-lo publicamente colocaria na internet justamente a peça sem os controles que todo o resto tem. A senha via Docker secret nunca foi controle suficiente sozinha — o controle real é a inalcançabilidade de rede.
- **Alternativas descartadas (todas verificadas no código, não supostas):**
  - **SSO OIDC (Grafana como cliente do authorization-server).** Era a opção *correta* se a exposição fosse necessária: daria login único e autorização por role. Custo: o claim `roles` **não existe no id_token** — `TokenCustomizerConfig.java:29` retorna cedo se o token não for `access_token`, e o mapeamento de papel do Grafana lê id_token/userinfo. Logo, mudança de contrato de token + ADR + novo `RegisteredClient` semeado no Postgres (com o problema conhecido de seed que não reconcilia).
  - **Subpath `/grafana` atrás da sessão do BFF** (padrão do ADR-020). Descartada porque o gateway é `anyExchange().authenticated()` (`SecurityConfig.java:118`) e **não faz `hasRole`** — enforcement de `ROLE_ADMIN` é só downstream (ADR-014), e o Grafana não tem downstream que cheque nada. Todo usuário `USER` autenticado veria os dashboards de infra. Exigiria ainda `GF_AUTH_ANONYMOUS_ENABLED`, o que deixa o Grafana sem senha para qualquer coisa que alcance `grafana:3000` na rede Docker.
  - **Hostname próprio no túnel** (escolha inicial do operador, revertida após ver o custo real). Abriria segunda superfície pública protegida só por senha, contrariando a premissa "só a borda pública é alcançável" (ADR-018/019, G10) — e esbarra no fato técnico abaixo.
  - **`cloudflared access` / rede privada do túnel.** Bloqueada pelo mesmo motivo que matou o Cloudflare Access no ADR-020: é feature do Zero Trust, cujo painel exige cartão de crédito mesmo no plano free.
- **Fato técnico verificado empiricamente (de valor duradouro):** o **`cloudflared` não interpola variáveis de ambiente no `config.yml`**. Testado com `tunnel ingress rule`: uma regra `hostname: ${GRAFANA_HOST}` com a env definida foi tratada como hostname **literal** e `https://grafana.exemplo.com` caiu no catch-all. Consequência: qualquer exposição futura por hostname próprio exige ou o domínio **literal** no repositório (contra a política de sigilo de `docs/DOMINIO.md`), ou um init-container que renderize o config a partir de um template versionado. Não é um detalhe recuperável por leitura da doc — só aparece testando.
- **Achado colateral corrigido na mesma leva:** o `docker-compose.override.yml` publicava a observabilidade **sem IP** (`"3000:3000"`, `"9090:9090"`, `"9411:9411"`), o que significa `0.0.0.0` — Grafana, Prometheus e Zipkin estavam acessíveis a **qualquer dispositivo da LAN** em dev. Prometheus e Zipkin não têm autenticação nenhuma e expõem métricas, traces, hostnames internos e topologia. Os três passaram a `127.0.0.1:`. Assimetria deliberada: gateway, interface, auth-server, user-service, notification-service, config-lb e discovery-servers permanecem em `0.0.0.0` — são as portas que um teste manual de outro dispositivo pode legitimamente querer alcançar em dev.
- **Armadilha registrada no compose:** listas de `ports:` são **concatenadas** entre arquivos, não substituídas. Não há duplicação hoje (a base não declara `ports` para o `grafana`), mas somar o overlay de deploy ao `docker-compose.override.yml` produziria bind duplicado na 3000. O comando documentado do deploy já exclui o override.
- **Verificado:** `docker compose -f docker-compose.yml config -q` OK (idêntico ao job `compose-validate`); no render de dev, `grafana`/`prometheus`/`zipkin` com `host_ip: 127.0.0.1` e `gateway`/`interface` inalterados; no render do deploy, o `grafana` é o **único** serviço com porta publicada, em `127.0.0.1`.
- **Se um dia precisar de acesso remoto:** malha privada (Tailscale/WireGuard, free sem cartão) em vez de rotear o túnel — dá acesso do celular/notebook sem nenhuma superfície pública e sem mudança no repositório.
- **Tipo:** decisão + endurecimento.

## [2026-08-05] · user-service · fechamento do G13 (listagem administrativa não auditada)

- **Gatilho:** pergunta aberta do operador sobre próximos passos com o sistema já online via Cloudflare Tunnel. Escolhido o G13 entre as opções apresentadas; implementação **direta** (fora do pipeline de agentes, decisão explícita do operador).
- **Defeito:** `AdminController.listAllUsers` era o único método de leitura do controller sem chamada ao `AuditService` — devolvia `Page<AdminUserResponseDTO>` (PII de vários titulares) deixando só um `LOGGER.info`, que é log operacional, não trilha LGPD. Desde o ADR-021 (que removeu `GET /v1/users`) era a **única** superfície de listagem do sistema: um token ADMIN comprometido exfiltrava a base inteira sem rastro.
- **Decisão de modelagem — uma entrada por titular retornado, não uma agregada por requisição.** O `AuditLog` tem `targetUserId` singular e o índice `(targetUserId, timestamp desc)` serve `GET /v1/admin/users/{id}/audit-logs`. Uma entrada agregada (`targetUserId=null`) registraria o evento mas **não apareceria no histórico de titular algum** — registraria a leitura sem responder "quem acessou o *meu* dado?", que é a pergunta que a trilha existe para responder. As alternativas (agregada; híbrida com array de IDs) foram descartadas por isso e porque ambas exigiriam campo novo no schema → ADR.
- **Sem ADR, deliberadamente:** nenhum campo novo no `AuditLog` nem no `AuditLogResponseDTO`; a mudança é um valor novo de enum (aditivo) e um método novo de serviço. O critério aplicado foi "ADR se o schema mudar" — declarado antes de implementar, não depois.
- **Implementação:** `AuditAction.ADMIN_LIST_USERS`; `AuditService.recordBulkFromJwt(action, jwt, List<AuditTarget>)` + record aninhado `AuditTarget(userId, email)` (segue o precedente de `AdminService.RoleUpdateResult`); `persistAll` com `insert` em **lote** e o mesmo isolamento de falha do `persist`; página vazia retorna sem tocar o repositório.
- **Dívida assumida no fechamento (registrada em `docs/SECURITY.md`):** volume. Uma página no teto (`MAX_AUDIT_PAGE_SIZE=100`) grava 100 entradas numa coleção que segue **sem TTL** — a dívida do ADR-011 ficou mais cara. Se crescer demais, a saída é TTL/arquivamento, **não** voltar à entrada agregada. Os filtros aplicados (`active`/`name`/`email`) **não** são registrados: exigiriam campo novo, e os titulares alcançados já são o dado forense relevante.
- **Guardas:** `AuditLogIntegrationTest.listagemAdministrativa_deveAparecerNoHistoricoDeCadaTitular` é o teste que prova a decisão de modelagem (consulta por `findByTargetUserId` acha a listagem) — é ele que falha se alguém "otimizar" para uma entrada agregada. Mais `AdminControllerTest` (entrada por titular; página vazia) e `AuditServiceTest` (lote único, e-mail mascarado, isolamento de falha, no-op em lista vazia). Suíte do user-service: 283 testes verdes, gate JaCoCo OK.
- **Tipo:** fechamento de gap de segurança/LGPD.

## [2026-08-05] · infra · fechamento do G14 + 2 correções documentais no SECURITY.md

- **Gatilho:** relatório de gaps pedido pelo operador. O relatório encontrou, além dos gaps já registrados, **três divergências doc↔código**; o operador pediu as correções (item 2) junto do fechamento do G14 (item 3). Implementação direta, fora do pipeline de agentes.
- **Decisões declaradas antes de implementar:** (a) porta de management **8181 uniforme** nos quatro serviços — containers distintos, sem colisão, e uma convenção só para memorizar; (b) `"/actuator/**"` removido do `permitAll()` dos três SecurityConfig, **incluindo o resíduo já inerte do gateway**; (c) **sem ADR** — nenhum contrato de API, schema ou claim muda, e o caminho de correção já estava escrito na própria linha do G14. Mesmo critério do G13 na véspera.
- **O gap estava subcontado e isso já era sabido.** O G14 nomeava `authorization-server` e `notification-service`; o `user-service` estava idêntico (`SecurityConfig:86`). `security-reviewer` (entrada de 2026-08-04, "Observação") e `senso-critico` registraram literalmente "G14 deve incluir o `user-service`" — e o achado **morreu na memória**, nunca chegou ao `SECURITY.md`. Variante do post-mortem do G1: lá a doc dizia fechado o que o código deixava aberto; aqui a doc descrevia um gap **menor** que o real. **Lição operacional:** o escopo de um gap verifica-se varrendo a superfície no código, não relendo o texto do gap — a mesma regra que já valia para "fechado".
- **Baseline medido antes de mexer (prova de que o gap era real, não teórico):** de dentro de um container qualquer da rede, `curl http://{user-service:8090,authorization-server:8082,notification-service:8095}/actuator/health` → **200** nos três, anônimo, e `/actuator/prometheus` devolvia o dump de métricas completo. É exatamente o cenário R-09 + override de dev.
- **Severidade em perspectiva (registrar para não inflar em releitura):** a exposure sempre foi só `health, info, metrics, prometheus` — nunca `env`/`heapdump`/`threaddump`/`beans`. Não havia vazamento de segredo nem de memória; o gap era de **reconhecimento** (topologia, hostnames internos, volume de tráfego).
- **Resíduo aceito:** a porta 8181 **não tem autenticação** — o controle é ela não ser publicada em compose algum + a rede Docker ser interna. Sob rede compartilhada com terceiros isso não bastaria (é o que o R-09 já descreve).
- **Fora do escopo, deliberadamente:** `management.server.port: -1` nos `application.yml` de teste de user-service e auth-server. O gateway precisa disso por causa do `RANDOM_PORT`; esses dois rodam em `webEnvironment = MOCK` **e** desligam o config-server (`spring.cloud.config.enabled: false`), então a propriedade nova nem chega neles — seria proteção para cenário que não ocorre.
- **`ServedConfigSecretLeakTest` não cobre isto:** filtra só o prefixo `springdoc.` (linha 50). Adicionar `management.*` ao filtro exigiria repensar as marcas de segredo (`port` não é segredo) — não feito.
- **Descoberta lateral, não tratada:** o stack em execução usa `docker-compose.yml + docker-compose.deploy.yml` e o `notification-service` está **healthy** — ou seja, o `MailHealthIndicator` passa e o SMTP do deploy **não** é mais o placeholder (commit `645c242`, "SMTP real"). O `docs/SECURITY.md` continua descrevendo o SMTP como placeholder bloqueante, com o efeito colateral do container `unhealthy`. **Provável quarta divergência doc↔código**, do mesmo tipo das duas corrigidas aqui — verificar contra os secrets antes de reescrever a linha do gap.
- **Verificação:** suítes verdes nos 5 módulos (user-service 283, auth-server 77, gateway 72, notification 12, config-server 6), gates JaCoCo OK; `docker compose config -q` na base+override e na base+deploy.
- **Tipo:** fechamento de gap de segurança + correção documental.
- **ERRO DE PREMISSA CORRIGIDO EM VOO (o achado mais valioso desta leva): `"/actuator/**"` no `permitAll()` NÃO é resíduo — é load-bearing.** O plano previa removê-lo dos três SecurityConfig como defesa em profundidade, na premissa (minha, por leitura estática) de que a linha ficara inerte com o actuator noutra porta. **Falso:** a chain de segurança do contexto **pai** governa **também** a porta de management (o contexto filho herda o filtro). Medido ao subir: user-service → **401** em `/actuator/health` e `/actuator/prometheus` na 8181, container `unhealthy`, Prometheus DOWN. Revertido nos três, com comentário de aviso em cada arquivo.
- **Agravante que quase passou: falso healthy no authorization-server.** Lá o `formLogin` redireciona (**302 → /login**) em vez de 401, e `curl -f` **não falha em 3xx** — o container reportava `healthy` enquanto o actuator não servia nada e o Prometheus recebia `text/html`. Se o teste tivesse sido só `docker compose ps`, a regressão teria passado. **Lição:** healthcheck com `curl -f` não distingue "saudável" de "redirecionado para o login"; ao mexer em segurança de rota de health, medir o **código HTTP e o corpo**, não o status do container.
- **Por que nenhum teste pegou:** nenhum dos 450 testes sobe a porta de management (user-service e auth-server rodam `webEnvironment = MOCK` e desligam o config-server). Suíte verde + `docker compose config -q` deram sinal verde num estado que quebrava o actuator dos dois serviços. Foi a subida real que pegou — e foi por pouco: o operador tinha escolhido "recriar agora" em vez de deixar para depois.
- **Efeito colateral novo, NÃO tratado (decidir depois):** na porta de tráfego, `/actuator/**` agora devolve **500** no user-service e no notification-service (404 limpo só no auth-server). É o mesmo defeito latente já conhecido do 405: o catch-all `Exception` do `@RestControllerAdvice` engole a exceção de "sem handler" antes do `DefaultHandlerExceptionResolver`. Não vaza nada (corpo é ProblemDetail genérico), mas polui métrica de 5xx e mente sobre a causa. Caminho: handler dedicado para `NoResourceFoundException`, como já se fez para `HttpRequestMethodNotSupportedException`.
- **Verificação end-to-end no stack de deploy real (base + deploy overlay, domínio público no ar):** baseline 200 anônimo nos três → depois 404/500 sem corpo de métrica; 8181 responde 200 nos três; 6/6 targets do job `microservices` UP (agora `:8181`); `label_values(jvm_memory_used_bytes, application)` segue listando os 6 serviços (dashboards intactos); site público OK (`/` 200, `/v1/users/me` 401 sem sessão, `/oauth2/authorization/gateway-client` 302); `/actuator/health` pela borda pública devolve o `index.html` do SPA (try_files), não o actuator.

## [2026-08-06] · user-service · notification-service · infra · Bloco 2: retry do outbox, 404 em path não mapeado, 19 vars inertes

- **Gatilho:** implementação do "Bloco 2" do plano de capacidade, com o pedido explícito de incorporar os **dois achados fora do escopo** do Bloco 1. O primeiro (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY` documentada mas inerte) já fora corrigido pontualmente; aqui foi **generalizado**. O segundo (500 em `/actuator/**` na porta de tráfego) estava registrado na entrada de 2026-08-05 como "efeito colateral novo, NÃO tratado (decidir depois)" — **fechado agora**, pelo caminho que aquela entrada já apontava.
- **Fora de escopo por decisão do operador:** réplicas do `cloudflared` (fica em 1 — HA de processo não compensa num deploy de máquina única, onde as réplicas morrem juntas) e métrica/contador do poller.

### 1. `OutboxRetryService` — emenda ao ADR-015

- **Restrição descoberta na exploração, que mudou o desenho:** o outbox persiste **só o `tokenHash`**; o token em claro nunca é salvo. Um poller **não consegue reenviar o e-mail original** — não tem o link. O retry **obrigatoriamente emite um token novo**, marcando o registro anterior como `SUPERSEDED`. Quem revisar isto tem de saber: não é escolha de conveniência, é consequência da decisão de segurança do próprio ADR-015.
- **Por que reverter o descarte original do ADR-015.** O argumento era "a UX já provê reenvio manual, tornando inútil o scan periódico". Ele pressupõe que o titular **saiba** que precisa pedir — e ele não sabe: um outbox `FAILED` é indistinguível de um e-mail atrasado. Passada a carência de 24h, a conta ficava permanentemente inacessível. Duas mudanças de contexto desde então: o envio automático no cadastro foi removido, e o SMTP real entrou em operação (commit `645c242`), transformando "notification-service fora" em modo de falha observável. O texto original foi **tachado, não apagado** — o histórico da decisão fica legível.
- **Armadilha de design não-óbvia (o ponto mais fácil de "otimizar" errado):** o teto de tentativas é contado sobre o **número de registros** do par (titular, tipo), via `countByUserIdAndType`, e **não** sobre o campo `attempts`. Cada retry cria um registro **novo** com `attempts=0` e `createdAt` fresco — qualquer limite ancorado no registro individual (contador **ou** janela de tempo sobre `createdAt`) **nunca expira** e a varredura reemite para sempre. Contar registros é auto-limitante e não exige campo novo (**sem mudança de schema**, portanto sem ADR próprio). Efeito colateral aceito: reenvios manuais consomem o mesmo orçamento.
- **Fail-CLOSED no Redis — o único ponto do sistema assim, e é intencional.** Cache, rate limit e revogação de token são fail-open porque um Redis fora não pode barrar autenticação. Aqui o inverso: sem o lock `SETNX` (`outbox_retry:lock`) confirmado, a varredura **não roda**. Falhar aberto com N instâncias significaria N e-mails duplicados por ciclo, e pular um ciclo de 5 min não custa nada. Há teste dedicado a essa assimetria — se alguém "uniformizar" para fail-open, ele falha.
- **ShedLock descartado:** `SETNX`+TTL com o `StringRedisTemplate` já injetado resolve, sem dependência nova. Segue o precedente do `ResendRateLimitService`/`LoginAttemptService`.
- **Único acréscimo de infraestrutura:** índice composto `type_status_createdAt` em `notificationOutbox` — o existente (`userId_type_status`) tem `userId` no prefixo e não serve a uma busca por (tipo, status), que viraria COLLSCAN.

### 2. `NoResourceFoundException` → 404 (user-service + notification-service)

- **Medido antes de mexer:** `user-service:8090/actuator/health` → **500**, `notification-service:8095/actuator/health` → **500**, `notification-service:8095/foo` → **500**; auth-server e gateway já devolviam 404. Causa exata do log: `NoResourceFoundException: No static resource actuator/health`.
- **É a mesma raiz do 405 já documentada no arquivo:** o advice não estende `ResponseEntityExceptionHandler`, e o `ExceptionHandlerExceptionResolver` roda **antes** do `DefaultHandlerExceptionResolver` — o catch-all `Exception` intercepta o que o Spring traduziria sozinho. **Regra geral para o futuro:** toda exceção que o Spring traduziria por conta própria precisa de handler explícito nesses dois advices.
- **No notification-service o alcance era maior:** sem Spring Security, **qualquer** path não mapeado caía no catch-all — cada probe de scanner virava stack trace em ERROR, afogando erro real no log.
- **Não confundir com o G14:** `"/actuator/**"` continua no `permitAll()` e **tem de continuar** (a chain do pai governa a 8181 — ver a entrada de 2026-08-05). O que mudou é só o status devolvido na porta de tráfego.

### 3. As 19 variáveis inertes — generalização do achado do Bloco 1

- **Método:** varredura de todo `${VAR}` lido pelos YAMLs do config-server, menos o que aparece em qualquer `environment:` dos três compose, menos o que chega como Docker secret via `configtree:` (caminho legítimo — é como o SMTP chega, e ignorá-lo teria produzido falso positivo). Resultado: **19 variáveis**, todas com linha própria no `docs/CONFIG.md`.
- **Não era cosmético.** O `CONFIG.md` dava instruções **impossíveis de cumprir**: `TERMS_VERSION` ("bump quando a política mudar" — exposição LGPD, é o que prova qual texto cada titular aceitou), `TRUSTED_CLIENT_IP_HEADER` ("trocar em deploy não-Cloudflare", ADR-010), `LOCKOUT_MAX_ATTEMPTS`/`LOCKOUT_DURATION` (anti-brute-force imutável sem rebuild), e `GATEWAY_TRUSTED_PROXIES`, que o `.env.example` convidava a descomentar sem efeito algum.
- **Lição, que é a mesma do G1 e do G14 em outra roupa:** corrigir **o caso visível** em vez da **classe** foi exatamente o que deixou o problema sobreviver ao Bloco 1. O certo é varrer a superfície no código assim que um exemplar aparece.
- **Armadilha na correção:** nunca escrever `${VAR:-}`. O default vazio injeta string vazia e **sobrescreve** o default do YAML em vez de deixá-lo valer — regressão silenciosa. O padrão adotado é espelhar o default do YAML no compose, ao preço de o default existir em dois lugares.
- **Verificação que faltou no Bloco 1 e agora existe:** `docker compose config | grep VAR` confirma valor resolvido e não-vazio; e o override foi provado de fato (`TERMS_VERSION=v2` → `v2` no config resolvido), não só assumido.
- **Tipo:** correção de bug latente + fechamento de dívida documental + reversão de decisão de ADR.

---

