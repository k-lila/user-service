# Roadmap — Passos Finais para a v1 do Blueprint

> Extraído do relatório de maturidade do projeto. Visão geral em [../CLAUDE.md](../CLAUDE.md).
>
> Este documento lista os passos que faltam para fechar a **primeira versão completa** do
> blueprint de sistema de usuários. Os itens estão ordenados **do mais simples ao mais
> custoso**. Quando todo o [Checklist](#checklist) estiver marcado, a v1 está completa.

## Índice

- [Informações relevantes](#informações-relevantes)
- [Passo a passo de cada ponto a ser implementado](#passo-a-passo-de-cada-ponto-a-ser-implementado)
  - [1. Corrigir o pacote órfão do discovery-server](#1-corrigir-o-pacote-órfão-do-discovery-server)
  - [2. Alinhar a versão do Spring Boot entre os serviços](#2-alinhar-a-versão-do-spring-boot-entre-os-serviços)
  - [3. Estreitar o tratamento de erro no login e documentar a leitura de inativos](#3-estreitar-o-tratamento-de-erro-no-login-e-documentar-a-leitura-de-inativos)
  - [4. Definir limites de recursos no Compose](#4-definir-limites-de-recursos-no-compose)
  - [5. Externalizar o sampling de tracing e dar storage ao Zipkin](#5-externalizar-o-sampling-de-tracing-e-dar-storage-ao-zipkin)
  - [6. Escrever os ADRs retroativos essenciais](#6-escrever-os-adrs-retroativos-essenciais)
  - [7. Plugar o JaCoCo com gate de cobertura](#7-plugar-o-jacoco-com-gate-de-cobertura)
  - [8. Adicionar pipeline de CI](#8-adicionar-pipeline-de-ci)
- [Checklist](#checklist)

---

## Informações relevantes

**O que é "v1 completa".** O blueprint já tem arquitetura, segurança aplicacional,
observabilidade e testes maduros. Esta lista fecha os gaps que separam o blueprint de
algo defensável como **base de produção**: automação de entrega, enforcement da cobertura
documentada, controles de operação e registro formal das decisões.

**Princípio de ordenação.** Do menor para o maior custo/risco. Os primeiros itens são
polimentos pontuais; os últimos (JaCoCo + CI) são transversais e mexem na esteira de
build de todos os módulos. JaCoCo vem **antes** da CI de propósito: o gate de cobertura é
invocado pela CI, então faz sentido tê-lo funcionando localmente primeiro.

**O que está fora de escopo da v1.** Os gaps de produção catalogados no `CLAUDE.md`
(seção _Gaps de Segurança Conhecidos_) que dependem de infra real do consumidor do
blueprint — cert ACME/domínios, chave JWK real, autenticação no Redis, troca do
`admin/admin` do Grafana. São **aceitos como exercício do consumidor**; a v1 apenas
garante que estejam nomeados e parametrizáveis, não resolvidos.

**Regras do projeto que continuam valendo.** Mudança de contrato de API ou de schema
exige ADR (`docs/adr/`); cobertura mínima de 80% (piso 70%) nas classes novas/alteradas;
registrar decisões em `.claude/memory/decisions.md`. Nenhum item deste roadmap altera
contrato de API ou schema — são polimento, config, observabilidade, documentação e
esteira.

**Convenção de esforço:** 🟢 trivial (minutos) · 🟡 pequeno/médio (horas) · 🔴 custoso (dia+).

---

## Passo a passo de cada ponto a ser implementado

### 1. Corrigir o pacote órfão do discovery-server

**Esforço:** 🟢 · **Tipo:** polimento · **Risco:** nenhum.

**Por quê.** O teste do discovery-server vive em `com.memelandia.discoveryserver`
enquanto o código de produção é `com.users.discoveryserver` — resíduo de copy-paste que
quebra a consistência de namespace do repo.

**Passo a passo.**
1. Mover `discovery-server/src/test/java/com/memelandia/discoveryserver/DiscoveryServerApplicationTests.java`
   para `.../com/users/discoveryserver/`.
2. Trocar a declaração `package com.memelandia.discoveryserver;` por `package com.users.discoveryserver;`.
3. Remover o diretório `com/memelandia` agora vazio.
4. Rodar `mvn -pl discovery-server test` e confirmar que o teste de contexto sobe.

**Arquivos afetados:** 1 (move + edição de `package`).

---

### 2. Alinhar a versão do Spring Boot entre os serviços

**Esforço:** 🟢 · **Tipo:** polimento · **Risco:** baixo (recompilação).

**Por quê.** O `authorization-server` está em Spring Boot **4.0.3**; os demais módulos em
**4.0.1**. Sem um POM agregador, esse drift cresce silenciosamente e abre brecha para
divergência de dependências transitivas entre serviços.

**Passo a passo.**
1. Escolher a versão alvo única (recomendado: a mais recente já validada — **4.0.3**).
2. Ajustar a `<version>` do `spring-boot-starter-parent` nos POMs que estiverem defasados
   (`config-server`, `discovery-server`, `gateway`, `user-service`).
3. Rodar `mvn -q -DskipTests package` em cada módulo para validar a resolução.
4. Rodar a suíte de testes completa para garantir ausência de regressão.
5. (Opcional, fora da v1) avaliar um POM-pai agregador para travar a versão num único lugar.

**Arquivos afetados:** até 4 `pom.xml`.

---

### 3. Estreitar o tratamento de erro no login e documentar a leitura de inativos

**Esforço:** 🟡 · **Tipo:** correção/decisão · **Risco:** baixo · **Cobertura:** ≥80% nas classes tocadas.

**Por quê.** Dois pontos sutis no relatório de maturidade:
- `AuthorizationService.loadUserByUsername` usa um `catch (Exception e)` largo que captura
  inclusive o `UsernameNotFoundException` limpo do fallback e o 404 de "usuário não
  existe", reembrulhando tudo em `RuntimeException("Erro de comunicação interna")`. Isso
  conflata "serviço fora do ar" com "usuário inexistente" e contradiz a intenção declarada
  de devolver `UsernameNotFoundException` imediatamente quando o user-service cai.
- `SearchService.searchById/searchByEmail` retornam usuários com `active=false` sem que
  isso esteja documentado como decisão (diferente da clareza do resto do projeto).
- **Fragilidade de teste descoberta no item 2:** o `AbstractIntegrationTest` do
  user-service **não isola o config import** — quando a stack Docker está no ar, o
  config-server serve a config de Redis **Sentinel** (hostnames internos do Compose) ao
  JVM de teste, que o Lettuce prioriza sobre o `spring.data.redis.host/port` do
  Testcontainer → `UnknownHostException: redis-sentinel-1` e ~32 erros de integração. O
  `AbstractIntegrationTest` do auth-server e do gateway já isolam (por isso passam com a
  stack no ar). É preciso aplicar o mesmo isolamento no user-service.

**Passo a passo.**
1. Em `AuthorizationService.loadUserByUsername`, deixar `UsernameNotFoundException` (e o
   `DomainEntityNotFound`/404 do Feign) propagarem como "credenciais inválidas", e
   restringir o `catch` largo às falhas reais de comunicação (timeout/circuit breaker
   aberto/IO). Manter a mensagem genérica voltada ao usuário (sem vazar qual condição
   ocorreu) — o refino é para diagnóstico/log, não para a resposta.
2. Decidir e **documentar** o comportamento de leitura de inativos: ou (a) filtrar
   `active=true` em `searchById/searchByEmail`, ou (b) mantê-lo como visão administrativa
   intencional. Registrar a escolha em `.claude/memory/decisions.md` e, se mudar
   comportamento observável, em `docs/SERVICOS.md`.
3. Isolar o config import no `AbstractIntegrationTest` do user-service (mesmo padrão do
   auth-server/gateway), para que a suíte seja determinística independentemente de a stack
   Docker estar no ar.
4. Cobrir os dois caminhos com testes (login com user-service indisponível vs. e-mail
   inexistente; leitura de usuário inativo conforme a decisão).

**Arquivos afetados:** `AuthorizationService.java`, possivelmente `SearchService.java`,
`user-service/.../integration/AbstractIntegrationTest.java`, testes correspondentes,
`decisions.md` (+ `docs/SERVICOS.md` se aplicável).

> Sem mudança de contrato de API → **não exige ADR**. Se a opção (a) alterar respostas de
> endpoint público, tratar como mudança de contrato e abrir ADR.

---

### 4. Definir limites de recursos no Compose

**Esforço:** 🟡 · **Tipo:** operação · **Risco:** baixo.

**Por quê.** O `docker-compose.yml` tem healthchecks (19) e restart policies (25), mas
**zero** limites de recurso. Em produção, um serviço com leak pode consumir todo o host e
derrubar os vizinhos. Faltam tetos de CPU/memória.

**Passo a passo.**
1. Definir um perfil de `limits`/`reservations` por classe de serviço (JVM vs. infra) —
   ex.: serviços Spring com `memory` compatível com o heap default + overhead; Mongo/PG/Redis
   conforme a carga esperada.
2. Adicionar `deploy.resources.limits` (e `reservations`) aos serviços no
   `docker-compose.yml`. Como o Compose standalone ignora parte de `deploy:`, considerar
   `mem_limit`/`cpus` para garantir o efeito fora do Swarm.
3. Validar com `docker compose -f docker-compose.yml config` (deve continuar exit 0) e
   subir a stack confirmando que nenhum serviço estoura o teto no boot.
4. Documentar os valores escolhidos e a racional em `docs/CONFIG.md`.

**Arquivos afetados:** `docker-compose.yml`, `docs/CONFIG.md`.

---

### 5. Externalizar o sampling de tracing e dar storage ao Zipkin

**Esforço:** 🟡 · **Tipo:** observabilidade · **Risco:** baixo.

**Por quê.** O sampling de tracing está hardcoded em `1.0` (100%) nos `*.yml` — custo
proibitivo em produção. E o Zipkin roda **in-memory**: traces somem no restart. Ambos
estão anotados como tech-debt em `decisions.md`.

**Passo a passo.**
1. Externalizar a probabilidade de sampling via env (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`),
   mantendo `1.0` como default de dev nos `config-server/.../config/*.yml` e permitindo
   override em prod.
2. Escolher e configurar um storage backend para o Zipkin (ex.: Elasticsearch, ou Cassandra),
   parametrizado por env — mantendo o in-memory como default de dev para não pesar a stack local.
3. Ajustar o serviço `zipkin` no `docker-compose.yml` (variáveis `STORAGE_TYPE` + conexão),
   sem publicar portas extras no base (prod-safe).
4. Validar em runtime: gerar tráfego, confirmar traces no Zipkin e a persistência após restart.
5. Atualizar `docs/CONFIG.md` (nova env) e registrar o fechamento dos dois itens em `decisions.md`.

**Arquivos afetados:** `config-server/.../config/*.yml`, `docker-compose.yml`,
`docs/CONFIG.md`, `decisions.md`.

---

### 6. Escrever os ADRs retroativos essenciais

**Esforço:** 🟡 · **Tipo:** documentação · **Risco:** nenhum.

**Por quê.** O processo do projeto exige ADRs para decisões arquiteturais, mas
`docs/adr/` só tem o `TEMPLATE.md`. Várias decisões estruturais já foram tomadas sem
registro formal. Escrever os ADRs retroativos valida que o processo documentado de fato
produz artefatos e dá rastreabilidade às escolhas centrais.

**Passo a passo.**
1. Selecionar as decisões estruturais a formalizar (mínimo recomendado):
   - **ADR — Padrão BFF** (gateway como cliente OAuth2; SPA sem JWT).
   - **ADR — Estado OAuth em PostgreSQL** (JDBC repositories do SAS; escala horizontal).
   - **ADR — Resiliência Feign com circuit breaker** (Resilience4j + fallback factory).
2. Para cada uma, copiar `docs/adr/TEMPLATE.md` para `docs/adr/ADR-NNN-<slug>.md` e
   preencher contexto, decisão, consequências e alternativas descartadas.
3. Cruzar com `.claude/memory/decisions.md` para não duplicar — o `decisions.md` aponta
   para o ADR formal quando houver.
4. (Opcional) adicionar um índice de ADRs no `docs/` ou no `CLAUDE.md`.

**Arquivos afetados:** novos `docs/adr/ADR-NNN-*.md`; possivelmente `decisions.md`/`CLAUDE.md`.

---

### 7. Plugar o JaCoCo com gate de cobertura

**Esforço:** 🟡 · **Tipo:** esteira · **Risco:** médio (pode reprovar build se a cobertura real ficar abaixo).

**Por quê.** O `CLAUDE.md` exige cobertura mínima de 80% (piso 70%), mas **nenhum POM tem
JaCoCo**. Hoje a regra é honra, não gate — impossível falhar o build por regressão de
cobertura. Pré-requisito para uma CI que signifique algo.

**Passo a passo.**
1. Adicionar o `jacoco-maven-plugin` em cada módulo back-end (ou num POM-pai, se o item 2
   tiver criado um), com `prepare-agent` + `report`.
2. Configurar a regra `check` com o limiar do projeto (80% linha/instrução; tratar 70%
   como piso bloqueante), ligada à fase `verify`.
3. Rodar `mvn verify` em cada módulo e medir a cobertura real. Onde estiver abaixo do
   piso, **escrever os testes faltantes** (a skill `/suggest-tests` ajuda) antes de fechar
   o item — não relaxar o limiar.
4. Confirmar que `mvn verify` passa em todos os módulos com o gate ativo.
5. Atualizar `docs/TESTES.md` com a instrução de cobertura e como ler o relatório.

**Arquivos afetados:** `pom.xml` de cada módulo (ou POM-pai), eventuais novos testes,
`docs/TESTES.md`.

---

### 8. Adicionar pipeline de CI

**Esforço:** 🔴 · **Tipo:** esteira · **Risco:** médio (orquestra todos os módulos + serviços de teste).

**Por quê.** Não há `.github/workflows/` — só `.github/modernize/`. Nada garante que os
testes rodem antes do merge; "262 testes" é uma asserção manual, não um gate. É o maior
salto de credibilidade por unidade de esforço, e materializa todos os itens anteriores
(testes, cobertura) como verificação automática.

**Passo a passo.**
1. Criar `.github/workflows/ci.yml` disparado em `push`/`pull_request`.
2. **Job back-end:** matriz (ou passos) por módulo Maven rodando `mvn verify` — o que já
   aciona o gate do JaCoCo (item 7). Garantir que o Testcontainers funcione no runner
   (Docker disponível no GitHub-hosted runner) para os testes de integração.
3. **Job front-end:** `npm ci` + `npm test` (Vitest) no `login-interface`, respeitando o
   threshold de 80%.
4. Publicar os relatórios (JUnit/JaCoCo/Vitest) como artefatos do workflow.
5. (Opcional) job de `docker compose -f docker-compose.yml config` para validar a topologia
   da stack a cada PR.
6. Tornar a CI verde um requisito de merge (branch protection) e atualizar o `README.md`
   com o badge/seção de CI.

**Arquivos afetados:** novo `.github/workflows/ci.yml`, `README.md`.

---

## Checklist

> Quando **todos** os itens abaixo estiverem marcados, o blueprint estará completo em sua
> primeira versão (v1).

**Polimento**
- [x] **1.** Teste do discovery-server movido para `com.users.discoveryserver` e `com.memelandia` removido.
- [x] **2.** Versão do Spring Boot única e idêntica em todos os módulos (4.0.3); suíte verde.

**Correção & decisão**
- [x] **3a.** `loadUserByUsername` distingue "user-service indisponível" de "usuário inexistente"; testes cobrindo ambos.
- [x] **3b.** Comportamento de leitura de usuários `active=false` decidido (filtrar), documentado (ADR-001 + SERVICOS.md) e testado.
- [x] **3c.** `AbstractIntegrationTest` do user-service isola o config import; suíte determinística com a stack Docker no ar.

**Operação**
- [x] **4.** `docker-compose.yml` com limites de CPU/memória por serviço; `config` exit 0; valores documentados em `CONFIG.md`.

**Observabilidade**
- [x] **5a.** Sampling de tracing externalizado por env (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`, default dev = 1.0); documentado em `CONFIG.md`/`.env.example`.
- [x] **5b.** Zipkin com storage backend parametrizável por env (`ZIPKIN_STORAGE_TYPE`+`ZIPKIN_ES_*`, default dev = `mem`); documentado. _Validação de persistência em runtime é exercício do consumidor (Opção A — sem ES local; ver decisions.md)._

**Documentação**
- [x] **6.** ADRs retroativos escritos em `docs/adr/` (BFF, estado OAuth no Postgres, resiliência Feign; + JWK persistente, canal interno, sessão Redis).

**Esteira**
- [x] **7a.** JaCoCo plugado em todos os módulos; regra `check` (piso 70% LINE, bundle) nos 3 de domínio; config/discovery report-only.
- [x] **7b.** `mvn verify` verde com o gate ativo; cobertura de linha medida bem acima do piso (user-service ~98%, auth-server ~95%, gateway 100%).
- [x] **8a.** `.github/workflows/ci.yml` rodando `mvn verify` (back, matrix por módulo) + `npm run coverage` (front, threshold 80%) + validação do compose em cada PR.
- [x] **8b.** CI verde exigida para merge (branch protection na `main`) e `README.md` atualizado (badge + seção CI).
