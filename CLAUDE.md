# CLAUDE.md

## Restrições de Execução

- Não refatore código fora do escopo explícito da tarefa pedida
- Não adicione tratamento de erro para cenários impossíveis
- Não crie arquivos sem ser pedido explicitamente
- Pergunte antes de agir se a tarefa tiver mais de 3 arquivos envolvidos
- Antes de cada alteração no código, apresente um relatório que aponte claramente: 1) razões dos novos códigos e/ou das modificações; 2) arquivos a serem criados, se houver; 3) arquivos a serem modificados, se houver
- Ao criar uma nova branch, pergunte seu nome
- Sempre que elaborar perguntas e fornecer as opções de resposta, sempre dê um panorama simples e resumido sobre a opção em si, com seus prós e contras.

---

## Visão Geral do Projeto

Sistema de microsserviços em Java + Spring para gerenciamento de usuários — a **v1 do blueprint
de um sistema de usuários**, funcional e em evolução ativa. Autenticação, registro, perfil e
controle de acesso funcionam **ponta a ponta**, e essa base é a **fundação sobre a qual novas
camadas de domínio são construídas**. A evolução é ativa; o cuidado é que **cada nova
implementação seja segura e compatível** com o que já existe — invariantes de design, contratos
entre serviços e controles de segurança preservados, com os controles de qualidade (testes, gate
de cobertura, observabilidade) mantidos ativos.

O front-end React (`login-interface`) usa o padrão **BFF**: o gateway é o cliente OAuth2, o SPA
usa sessão por cookie e **não** manuseia JWT.

**Mapa de documentos:**

- [README.md](README.md) — pré-requisitos e execução (humano)
- [docs/SERVICOS.md](docs/SERVICOS.md) — referência da API (endpoints, schema MongoDB, cache)
- [docs/CONFIG.md](docs/CONFIG.md) — variáveis de ambiente
- [docs/CONVENCOES.md](docs/CONVENCOES.md) — convenções e invariantes de design
- [docs/TESTES.md](docs/TESTES.md) — estratégia de testes
- [docs/LOGS.md](docs/LOGS.md) — estratégia de logs
- [docs/SECURITY.md](docs/SECURITY.md) — controles ativos e gaps de segurança conhecidos
- [docs/ORQUESTRACAO.md](docs/ORQUESTRACAO.md) — sistema de orquestração de agentes
- [docs/BLUEPRINT.md](docs/BLUEPRINT.md) — catálogo de infraestrutura genérica vs. código específico do domínio usuário
- [docs/adr/](docs/adr/) — Architecture Decision Records (template em `docs/adr/TEMPLATE.md`); criados pelo `techlead` em mudanças de contrato/schema. Catálogo completo, em ordem, no próprio diretório.

---

## Arquitetura

```
login-interface (React)
        │
        ▼
    gateway :8081          ← único ponto de entrada externo
    ├── /v1/users/register  → user-service
    ├── /oauth2/**          → authorization-server
    ├── /login              → authorization-server
    └── /v1/users/**        → user-service
        │
        ├── authorization-server :8082
        │       ├── chama user-service via Feign (circuit breaker Resilience4j + UserClientFallbackFactory)
        │       ├── PostgreSQL (auth-postgres :5432 — estado OAuth: client/authorizations/consents)
        │       └── Redis Sentinel (sessão de login/consent)
        │
        ├── user-service :8090
        │       ├── MongoDB replica set rs0 (mongo-1/2/3 — persistência)
        │       ├── Redis Sentinel (cache + rate limiting)
        │       └── chama notification-service via Feign assíncrono (circuit breaker Resilience4j + NotificationClientFallbackFactory)
        │
        ├── notification-service :8095  (stateless; SMTP via JavaMailSender; nunca exposto pelo gateway)
        │
        ├── discovery-server-1 :9091 [· discovery-server-2 :9092 no --profile ha]
        ├── config-lb :8888 → config-server (replicável por --scale, resolvido por DNS)
        ├── zipkin :9411 · prometheus :9090 · grafana :3000
```

**Tecnologias:**

- **Back-end:** Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.0, Maven
- **Dados:** MongoDB (dados de usuário), PostgreSQL (estado OAuth), Redis (cache, rate limiting e sessão)
- **Front-end:** React 19 + TypeScript + Vite + TailwindCSS 4
- **Orquestração:** Docker Compose

### Fluxo de Autenticação (OAuth2 / BFF)

```
1. SPA → "Login" → /oauth2/authorization/gateway-client (gateway inicia o oauth2Login)
2. Gateway → redireciona o browser ao authorization-server (authorization-uri http://localhost:8082/oauth2/authorize)
3. authorization-server → exibe form de login
4. Usuário → submete credenciais
5. authorization-server → chama user-service /internal/users/email/{email} (Feign)
6. user-service → retorna dados do usuário (hash, roles)
7. authorization-server → valida senha (BCrypt), gera JWT com claims; redireciona ao gateway com o código
8. Gateway → troca o código por token (back-channel interno authorization-server:8082), guarda na sessão (cookie SESSION)
9. Requests subsequentes do SPA → cookie de sessão; o gateway relaya o JWT via TokenRelay downstream
10. Logout → POST /logout encerra a sessão e dispara o RP-Initiated Logout no IdP (end_session_endpoint)
```

**Claims customizados no JWT (`TokenCustomizerConfig`, só no `access_token` — nunca no `id_token`):** `userID` (ID no MongoDB), `roles` (ex. `["USER"]`), `permissions` (**derivadas das roles**, não fixas: `USER` → `["users.read","users.write"]`; `ADMIN` → `["users.read","users.write","users.delete"]`) e `scope` (os scopes autorizados). As coleções são serializadas como `ArrayList` de propósito — o `PolymorphicTypeValidator` do SAS rejeita `ImmutableCollections$*`/`Set$*` na releitura do Postgres.

---

## Desenvolvimento local

### Subir tudo com Docker

**Pré-requisito obrigatório (uma vez):** a base é **secrets-native** ([ADR-009](docs/adr/ADR-009-base-secrets-native-docker-secrets.md)) — sem `./secrets/` o `up` **falha**. Gere os segredos + o par JWK antes do primeiro `up`:

```bash
infra/secrets/gen-secrets.sh   # defaults de DEV; em prod exporte cada segredo com valor forte
docker compose up -d --build
```

**Elasticidade — piso mínimo por default ([ADR-024](docs/adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md)):** o `up` sem argumento sobe **19 serviços**, com Mongo em replica set `rs0` de **um membro** e Redis com **um** nó e **um** Sentinel. Degenerado, não standalone — é o que mantém `MONGODB_URI` (`replicaSet=rs0`) e `spring.data.redis.sentinel.*` **idênticos** do piso ao topo, sob o princípio *encolher é remover nó, crescer é adicionar nó, nunca reconfigurar cliente*. Crescer: `--profile ha` (redundância de dados, 26 serviços — o replica set cresce sozinho via `rs.reconfig` aditivo em `infra/mongo/rs-reconcile.sh`, que descobre membros por DNS) e `--scale <svc>=N` (camada de aplicação + `config-server`). Três coisas a não regredir: **`--scale` exige `-f docker-compose.yml` explícito** (o override publica portas fixas e a 2ª réplica falha no bind; listas de `ports:` são concatenadas no merge, nunca removidas); **nenhum serviço fora do profile `ha` pode declarar `depends_on` para um dentro dele** (o comportamento do Compose nesse caso variou entre versões — a regra torna a questão irrelevante); e o piso mínimo **não é HA** (sem failover de Mongo/Redis). Eixos por componente em [docs/BLUEPRINT.md § E](docs/BLUEPRINT.md).

O `docker-compose.yml` é **base prod-safe** (publica só `gateway:8081` e `interface`); o `docker-compose.override.yml` (auto-carregado por `docker compose up`) republica as portas internas para **dev**. Para um deploy prod-like (só a borda exposta), rode `docker compose -f docker-compose.yml up` (ignora o override) com um `.env` setando as URLs públicas — ver `.env.example`. Todos os serviços declaram `cpus` / `mem_limit` / `mem_reservation` (chaves de nível de serviço, não `deploy.resources`) — perfis e racional em [docs/CONFIG.md § Limites de recursos](docs/CONFIG.md#limites-de-recursos-cpu--memória). Dois pontos a **não** regredir ali: os 4 apps Spring usam o perfil `x-res-jvm` (`cpus: 2.0`), **separado** do `x-res-app` dos nós Mongo — fundi-los dobraria a quota do MongoDB sem motivo; e o heap/coletor vêm de `JAVA_TOOL_OPTIONS` explícito (`-XX:+UseG1GC -XX:MaxRAMPercentage=60`) porque, sem ele, a ergonomia da JVM classifica o container como *client-class machine* e cai em `UseSerialGC` com heap de 256m.

**Segredos (base secrets-native, gap 0.3 RELATORIOA):** os segredos saem do `.env` plano para **Docker secrets** em `./secrets/` (gitignorado, gerados por `infra/secrets/gen-secrets.sh`), montados em `/run/secrets/`. Consumo: Spring via `spring.config.import=configtree:/run/secrets/` (nome do arquivo = placeholder); postgres/mongo via `*_FILE`; redis/sentinel via `$(cat ...)`; redis-exporter via `--redis.password-file` (JSON); grafana via `__FILE`; prometheus via `password_file`. O par JWK também é secret (`jwk_private`/`jwk_public`). Mecanismos e tabela em [docs/CONFIG.md § Docker secrets](docs/CONFIG.md#docker-secrets-base-secrets-native).

**TLS/HTTPS na borda:** terminação TLS é responsabilidade da **Cloudflare** (ver overlay de deploy abaixo); o tráfego interno permanece HTTP. Não há mais overlay de TLS local em dev.

**Deploy via Cloudflare Tunnel:** overlay `docker-compose.deploy.yml` sobe o `cloudflared` como **named tunnel com domínio fixo**, no modo **locally-managed** — criado pela CLI (`cloudflared tunnel create`), não pelo painel Zero Trust (que exige cartão de crédito mesmo no plano free). O comando é `tunnel --config /etc/cloudflared/config.yml run ${TUNNEL_ID}`; a autenticação é o credentials-file JSON (Docker secret `CLOUDFLARE_TUNNEL_CREDENTIALS`, referenciado de dentro do config — a imagem é distroless, sem shell). A topologia é de **hostname único**: o túnel entrega em `interface:80` (o nginx do SPA), **não** no gateway, e o nginx faz proxy same-origin de 9 paths ao gateway: `/v1/users`, `/v1/admin`, `/oauth2`, `/login` (prefixo — subsume `/login?error` e `/login/oauth2/**`), `/default-ui.css` (match exato — CSS do formulário do IdP), `/logout`, `/connect/`, `/swagger-ui` e `/v3/api-docs`. Consequências: browser e API na mesma origem (CORS deixa de ser exercitado; cookies `SESSION`/`XSRF-TOKEN`/`AUTHSESSION` no mesmo host com `SameSite=Lax` natural), e nem o gateway nem o auth-server ficam alcançáveis de fora. Todas as envs de borda derivam de `${PUBLIC_ORIGIN}` (fail-fast), com `APP_COOKIE_SECURE=true` e `SERVER_FORWARD_HEADERS_STRATEGY=framework`. `AUTH_ISSUER`/`AUTH_ISSUER_URI` permanecem **internos** — o JWT é validado pela rede Docker. As **ingress rules são versionadas** em `infra/cloudflared/config.yml` (só a regra catch-all para `interface:80` — sem `hostname:` e sem o ID do túnel, porque o domínio real não entra no repo; `TUNNEL_ID`/`PUBLIC_ORIGIN` vivem no `.env`). Roteiro de subida — ordem obrigatória: criar o túnel pela CLI → regenerar segredos → `down -v` → `up` único — em [README § 2b](README.md). O doc OpenAPI do Swagger requer `API_BASE_URL`/`AUTH_URL`/`AUTH_TOKEN` no **user-service** apontando à origem pública, senão o "Try it out" dispara para `localhost` (mixed content); `/swagger-ui/*` e `/v3/api-docs/*` **exigem a sessão OAuth2 do BFF** ([ADR-020](docs/adr/ADR-020-swagger-atras-da-sessao.md)) — o Cloudflare Access previsto para eles segue bloqueado (Zero Trust exige cartão), mas o gate deixou de depender dele.

### Ordem manual (sem Docker)

1. config-server → 2. discovery-server → 3. authorization-server → 4. user-service → 5. gateway (`mvn spring-boot:run` em cada) → 6. login-interface (`npm run dev`)

Variáveis de ambiente: ver [docs/CONFIG.md](docs/CONFIG.md). Em dev manual do BFF, exporte `OAUTH_REDIRECT_URI=http://localhost:5173/login/oauth2/code/gateway-client`.

### URLs de acesso

| Serviço       | URL                                         |
| ------------- | ------------------------------------------- |
| Gateway / API | http://localhost:8081                       |
| Swagger UI    | http://localhost:8081/swagger-ui/index.html |
| Eureka        | http://localhost:9091                       |
| Zipkin        | http://localhost:9411 🔒                     |
| Prometheus    | http://localhost:9090 🔒                     |
| Grafana       | http://localhost:3000 🔒 (user do `.env`, senha do secret `GRAFANA_ADMIN_PASSWORD`) |
| Front-end     | http://localhost:5173                       |

🔒 **Só a partir da própria máquina.** As três portas de observabilidade são publicadas presas ao
loopback (`127.0.0.1:PORTA:PORTA`), tanto em dev (`docker-compose.override.yml`) quanto no deploy
(`docker-compose.deploy.yml`). Nenhuma tem lockout, rate limit ou MFA — Prometheus e Zipkin não têm
autenticação alguma — e não há regra de ingress para elas no túnel. Republicar sem o IP (`- "3000:3000"`)
devolve o acesso à LAN inteira. Ver [docs/SECURITY.md](docs/SECURITY.md).

### Observabilidade

- **Zipkin** — B3; sampling default dev 100% (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY:1.0` nos `*.yml` do config-server); reduza via env em prod (ex.: `0.1`) — a variável agora é **efetivamente repassada** pelo anchor `x-spring-app-env` do compose aos 4 apps Spring: antes era documentada aqui e no `.env.example` mas **inerte** (sem `env_file` e ausente de todo `environment:`), então o deploy rodava a 100% independentemente do `.env`; storage default `mem` (in-memory, traces somem no restart), parametrizável para Elasticsearch externo via `ZIPKIN_STORAGE_TYPE`/`ZIPKIN_ES_*` (ver `docs/CONFIG.md`)
- **Prometheus** — `/actuator/prometheus`, scrape 5s; job `microservices` (discovery estático nas portas principais `9091`/`9092`; auth-server, user-service, notification-service e gateway por **`dns_sd_configs`** na **porta de management `8181`**, não na de tráfego — ver G14 fechado em `docs/SECURITY.md`. A descoberta por DNS é o que dá **um target por réplica** (ADR-024): com `static_configs` o nome resolvia para UMA réplica por scrape e, a partir de N > 1, as séries alternavam entre processos a cada 5s) + job `config-server` (`dns_sd_configs` sobre `config-server`, `basic_auth` — o `/actuator/prometheus` do config-server fica atrás de HTTP Basic; a senha já vem do Docker secret via `password_file: /run/secrets/CONFIG_SERVER_PASSWORD`, não inline); 3 exporters de infra (sem `ports:` no base — prod-safe):
  - `mongodb-exporter:9216` (percona/mongodb_exporter:0.43.1) — RS via seed único `mongo-1:27017` + `replicaSet=rs0`; credencial via env (`MONGODB_URI`)
  - `postgres-exporter:9187` (prometheuscommunity/postgres-exporter:v0.16.0) — `DATA_SOURCE_NAME` para `auth-postgres:5432/authdb`
  - `redis-exporter:9121` (oliver006/redis_exporter:v1.62.0) — modo multi-target (`/scrape`): 3 data nodes (`redis-1/2/3:6379`) + 3 sentinels (`redis-sentinel-1/2/3:26379`); relabel `__address__`→`instance` (sem colisão)
- **Grafana** — dashboards pré-provisionados, **revisados para a elasticidade do ADR-024**: `dashboardHTTP.json` (HTTP de borda) e `dashboardJVM.json` (heap/non-heap/GC/threads/CPU) têm **duas** template vars — `application` e `instance` (multi-valor, `All` por default, encadeada em `application`) — porque com N réplicas o agregado responde *"o serviço está bem?"* e só a quebra por réplica responde *"qual réplica está mal?"*. Três painéis existem por causa da escala: **"RPS por réplica"** (HTTP — é a pergunta "a carga está distribuída?", medida em 11/10/9 de 30 requisições via Eureka), **"Réplicas atendendo"** (HTTP — distingue *registrada* de *usada*; diverge de "Réplicas" no JVM quando o balanceamento falha) e **"Heap usado por réplica vs teto"** (JVM — o painel antigo comparava `sum(usado)` com `sum(máximo)`, e como os dois lados triplicam com 3 réplicas, uma réplica a 95% de heap sumia na média). `p95`/`p99` seguem agregando **buckets** antes do `histogram_quantile` — quebrar por réplica e tirar média seria matematicamente errado; há comentário no arquivo dizendo isso. `dashboardMongo.json` ganhou o par **"Membros saudáveis" × "Membros configurados"**, cuja divergência (3 configurados / 1 saudável) é a assinatura exata do Mongo somente-leitura descrito no runbook de encolhimento; `dashboardPostgres.json` ganhou **"Headroom de conexões"** e **"Conexões vs teto"**, que tiram de `docs/CONFIG.md` e põem em painel o único limite de escala cuja violação falha em produção (N réplicas × `AUTH_DB_POOL_SIZE` ≤ `max_connections`). **Regra a não regredir:** *threshold que assume o topo da escala é bug de dashboard* — o `dashboardRedis` ficava vermelho permanente na topologia **default** porque contava os 6 nós do `ha` e o piso tem 2; agora são dois contadores (data nodes / sentinels, separados pela porta no label `instance`) verdes a partir de 1. Os cinco arquivos **declaram `datasource: "Prometheus"` por painel** — a dívida do `isDefault: true` está fechada.
- **SLOs** — 50ms / 100ms / 200ms / 500ms / 1s / 2s

---

## Serviços

> Referência da API (endpoints, schema, cache) em [docs/SERVICOS.md](docs/SERVICOS.md). Aqui ficam só as responsabilidades não-óbvias e os arquivos críticos a preservar.

### config-server (8888)

- **Config centralizada** via `classpath:/config`; os demais serviços importam com `spring.config.import=optional:configserver:${CONFIG_SERVER_URL}`.
- Arquivos em `config-server/.../config/{servico}.yml`.
- **Replicável** ([ADR-024](docs/adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md)): serviço **único** `config-server` (não mais o par nomeado `config-server-1/2`), atrás de `config-lb` (nginx). Quantidade é `--scale config-server=N`, default 1. Stateless — qualquer instância serve a mesma config. O `config-lb` resolve por **DNS** (`resolver 127.0.0.11` + `proxy_pass` sobre variável, o mesmo padrão do nginx do SPA), com `proxy_next_upstream` preservando o failover: **não volte ao bloco `upstream` estático** — ele resolve os nomes no start e faz o nginx **recusar iniciar** se um não resolver, que foi o que tornava o piso mínimo impossível.
- **Deve subir primeiro** — todos os demais dependem dele via `config-lb`.
- **HTTP Basic:** `SecurityConfig` exige autenticação no endpoint (`/actuator/health` aberto p/ healthchecks; CSRF off — cliente é máquina). Os clientes enviam `spring.cloud.config.username/password`; par único `CONFIG_SERVER_USERNAME`/`CONFIG_SERVER_PASSWORD` (default dev no `application.yml`, sem default no compose → fail-fast).

### discovery-server (9091 / 9092)

- **Netflix Eureka HA** — dois nós em peer replication (`discovery-server-1:9091`, `discovery-server-2:9092`); cada instância se registra na outra via `EUREKA_PEER_URL`.
- `EUREKA_URI` nos demais serviços lista ambas as instâncias (CSV).
- Sobe após `config-lb`.

### authorization-server (8082)

- **Tipo:** OAuth2 Authorization Server (Spring Security). Fluxo authorization_code + PKCE obrigatório (`requireProofKey(true)`) + refresh_token. Scopes do `gateway-client`: `openid`, `profile`, `users.read`, `users.write`. Autenticação do cliente: `client_secret_basic` **e** `client_secret_post`.
- **Estado OAuth em PostgreSQL** (escala horizontal):
  - `JdbcRegisteredClientRepository` + `JdbcOAuth2AuthorizationService` + `JdbcOAuth2AuthorizationConsentService` (`OAuth2ClientConfig.java`).
  - `gateway-client` **semeado idempotentemente** na subida (`findByClientId` → `save`), preservando `redirectUri` (inclusive Swagger) e scopes; `redirectUri`/`postLogoutRedirectUri` externalizáveis via `OAUTH_CLIENT_REDIRECT_URIS`/`OAUTH_CLIENT_POST_LOGOUT_URIS` (ver `docs/CONFIG.md`), sem reconciliação pós-seed. **O seed é um check-then-act e a concorrência o atravessa (ADR-022):** quem impede a duplicata é o índice **único sobre `client_id`** acrescentado ao schema (`CREATE UNIQUE INDEX IF NOT EXISTS`, que ao contrário de `CREATE TABLE IF NOT EXISTS` também se aplica a tabela já existente) — sem ele, N instâncias subindo juntas gravam N linhas com o mesmo `client_id`, hashes BCrypt distintos do mesmo segredo, **sem quebrar nada** (todos validam) e com `findByClientId` devolvendo uma linha arbitrária de query sem `ORDER BY`. O `seedGatewayClient` absorve a violação — que chega como `IllegalArgumentException` (o `assertUniqueIdentifiers` do SAS, ele próprio outro check-then-act, por isso **não** substitui a constraint) **ou** `DuplicateKeyException` (ambas passaram por aquele check) — e relê o registro para confirmar que foi a corrida; se o client não estiver lá, propaga, senão o serviço subiria sem cliente OAuth nenhum.
  - **Purga do estado OAuth (`OAuthStatePurgeService`, ADR-022):** o `JdbcOAuth2AuthorizationService` **nunca** apaga linha — sem varredura, `oauth2_authorization` cresce uma linha por login, para sempre (medido: 8 de 8 linhas totalmente expiradas e intocadas). `@Scheduled` a cada `purge-interval` (6h; o módulo ganhou `@EnableScheduling`) apaga em lote, com lock `SETNX` **fail-closed** como o `OutboxRetryService`. O critério é o **`GREATEST` das seis colunas de expiração**, não uma delas: filtrar por `access_token_expires_at` apagaria autorizações com refresh token vivo, deslogando usuários ativos. O `COALESCE(..., 'epoch')` trata nulo como "expirou há muito" (nulo = grant não usado), o que tornaria uma linha toda-nula elegível — daí a disjunção `IS NOT NULL`, sem a qual o `DELETE` alcançaria estado vivo. `LIMIT` no subselect limita a transação. **Sem índice para o predicado, de propósito:** é expressão sobre seis colunas, e a própria purga mantém a tabela pequena.
  - Schemas SAS 7.0.3 adaptados (`blob`→`text`, `timestamp`→`timestamptz`, `IF NOT EXISTS`) em `src/main/resources/schema/`, aplicados via `spring.sql.init` (`continue-on-error: true`).
- **Chave JWT persistente** (`JWKConfig.java`):
  - Par RSA fixo com `kid` estável (`user-service-key`), carregado de PEM via `RsaKeyConverters` — em vez de gerar por boot.
  - As chaves **não vivem no repositório** (gap 0.1 fechado, ADR-005): são geradas por `infra/jwk/gen-keys.sh`, `src/main/resources/keys/` está no `.gitignore`, o CI gera um par efêmero por run e no compose o par é Docker secret (`jwk_private`/`jwk_public` via `JWK_PRIVATE_KEY=file:/run/secrets/jwk_private`). O que é parametrizável por `JWK_*` é a **origem** do PEM.
- **Sessão HTTP no Redis** via Spring Session:
  - `@EnableRedisHttpSession` no `SecurityConfig` — exige anotação explícita no Spring Boot 4.0.
  - Cookie renomeado para **`AUTHSESSION`** (`CookieSerializer`) para não colidir com o `SESSION` do gateway (ver [docs/CONVENCOES.md](docs/CONVENCOES.md)); flag `Secure` parametrizável via `app.cookie.secure` (`setUseSecureCookie`, default false p/ dev HTTP), simétrica ao gateway — o overlay de deploy (Cloudflare) liga ambos via `APP_COOKIE_SECURE=true`.
- **Credenciais e token:** busca credenciais via Feign (`GET /internal/users/email/{email}`); customiza o JWT em `TokenCustomizerConfig.java` (**arquivo crítico**) com `userID`, `roles`, `permissions`.
- **Resiliência Feign:** `IUserClient` tem `fallbackFactory = UserClientFallbackFactory.class`. Circuit breaker Resilience4j (`spring.cloud.openfeign.circuitbreaker.enabled=true`, group por nome do client) com a config nomeada `configs.user-service` (com group habilitado, `instances.*` é inerte — use `configs.*`): janela 10, `minimumNumberOfCalls` 10, threshold 50%, open 10s, timeout 3s. Indisponibilidade do user-service falha imediatamente em vez de travar em timeout, lançando `UserServiceUnavailableException` (estende `InternalAuthenticationServiceException`, ADR-021) — **não** `UsernameNotFoundException`, que o `DaoAuthenticationProvider` converteria em `BadCredentialsException` e alimentaria o contador de lockout, bloqueando contas legítimas por 15 min durante um outage. **O fallback distingue duas causas** (o Feign entrega ambas pelo mesmo caminho): `FeignException.NotFound` (404 — titular inexistente ou inativo) é resultado de negócio e vira `UsernameNotFoundException`, que **conta** no lockout (atrito anti-enumeração); qualquer outra causa (500, 503, timeout, conexão recusada, circuito aberto) é indisponibilidade e **não** conta. Nunca use `instanceof FeignException` genérico nesse teste — 500/503 também são `FeignException` e voltariam a alimentar o lockout. Complemento obrigatório no `configs.user-service`: `ignoreExceptions: [feign.FeignException$NotFound]`, senão o 404 de negócio abre o circuito (DoS por typo); as duas coisas são interdependentes — `ignoreExceptions` não impede o fallback de ser invocado. O `AuthorizationService` captura `AuthenticationException` e propaga sem reembrulhar (o catch **tem** de ser o supertipo: `UserServiceUnavailableException` não é subtipo de `UsernameNotFoundException` e cairia no `catch (Exception)`, regredindo o fix em silêncio). A exceção **não encadeia `cause`** — o failure handler a guarda na sessão Redis e a cadeia Feign/Resilience4j não é serializável. Só exceções inesperadas viram `UsernameNotFoundException` genérica, sem vazar a causa.
- **Propagação de trace no Feign (`FeignTracingConfig.java`):** na cadeia Feign + circuit breaker a instrumentação automática (feign-micrometer) registrava o span cliente no trace correto, mas **não emitia os headers B3** — o user-service recebia a request sem `X-B3-*` e abria um trace **órfão**. `FeignTracingConfig` adiciona um `RequestInterceptor` que injeta o contexto de trace corrente no template via o `Propagator` do Micrometer (formato `b3`); assim o user-service (`consume: b3`) continua o trace e seu span vira filho do auth-server. Degrada graciosamente quando não há contexto ativo.
- **Lockout anti-brute-force:** `LoginAttemptService` mantém contador de falhas no Redis por par **(conta, IP)** — chave `sha256(emailLower|ip)`, janela fixa (TTL 15 min na 1ª falha), lockout após 5 falhas (`security.lockout.*`). `LoginAttemptListener` conta só `AuthenticationFailureBadCredentialsEvent` de form login; `AuthorizationService` devolve `accountNonLocked=false` quando bloqueado → `LockedException` antes da checagem de senha (mensagem genérica). **IP não-falsificável (ADR-010):** a fonte de IP é o header confiável `security.trusted-client-ip-header` (default `CF-Connecting-IP`, que a Cloudflare sobrescreve), com fallback em `getRemoteAddr()` sob `server.forward-headers-strategy=framework` (agora na **base** do config-server, não só nos overlays); o `X-Forwarded-For` bruto não é mais lido (`cloudflared` faz append → leftmost spoofável). `ClientIpResolver.currentIp(header)` continua a fonte ÚNICA do lockout.
- **Gate de e-mail verificado no login + grace period (ADR-015):** `AuthorizationService.loadUserByUsername` mapeia `AuthDTO.emailVerified` para `enabled` do `UserDetails` — `emailVerified=false` bloqueia o login (`DisabledException`, mensagem genérica) antes da checagem de senha. Mitigado por **janela de carência de 24h** (`security.email-verification.grace-period`) desde `AuthDTO.registrationDate` (campo aditivo) — login funciona dentro da janela mesmo sem confirmação, evitando conta permanentemente inacessível se o e-mail nunca chegar.
- **Fechamento do refresh para revogação ativa (ADR-017):** `RevocationRefreshGuard` lê o epoch de revogação por usuário no Redis (mesma chave que o user-service grava); no grant `refresh_token`, `TokenCustomizerConfig` aborta a reemissão (`OAuth2AuthenticationException`/`invalid_grant`) quando a revogação é mais recente que o refresh token apresentado. Sem isso, o gateway renovaria o access token silenciosamente, perpetuando credenciais de um usuário já revogado/desativado. Fora do refresh (authorization_code) o epoch é irrelevante (o login acabou de validar o estado). Fail-open.

### user-service (8090)

- **Domínio central:** CRUD de usuários. MongoDB (coleção `users`). Cache Redis: `usersById`, `usersByEmail`, `authByEmail`.
- **Controllers:**
  - `UserController` — público, via gateway. Opera **exclusivamente sobre o próprio titular autenticado** (`/me`, update, `/remove/me`, `/delete/me`, registro, verify/resend). A leitura por id/e-mail de terceiro saiu daqui (ADR-016) e a **listagem `GET /v1/users` foi removida** (ADR-021 — devolvia PII de toda a base ativa a qualquer `USER`, era o resíduo do G1 que o ADR-016 não viu); ambas viraram ADMIN-only no `AdminController`. O path responde **405** ao `GET`, via handler dedicado no `GlobalExceptionHandler` — sem ele o advice, que não estende `ResponseEntityExceptionHandler`, devolveria 500 pelo catch-all. Pela **mesma razão** há handler de `NoResourceFoundException` → **404** (user-service **e** notification-service): com o actuator movido para a 8181 (G14), `/actuator/**` continua no `permitAll()` mas não é mais mapeado na porta de tráfego, e o request atravessava a segurança até o catch-all, virando 500 + stack trace em ERROR a cada probe — no notification-service, que não tem Spring Security, isso valia para **qualquer** path não mapeado. Ao acrescentar advice novo, lembre que este é o padrão: o `ExceptionHandlerExceptionResolver` roda antes do `DefaultHandlerExceptionResolver`, então **toda** exceção que o Spring traduziria sozinho precisa de handler explícito aqui.
  - `InternalUserController` — `GET /internal/users/email/{email}`, **não exposto pelo gateway**, só para o auth-server via Feign. Protegido por `X-Internal-Token` (`InternalTokenFilter`); acesso sem o header → 403.
  - `AdminController` — `/v1/admin/**`, todo método `@PreAuthorize("hasRole('ADMIN')")` (ADR-014): listagem completa incl. inativos com filtros (`GET /v1/admin/users`, audita `ADMIN_LIST_USERS` — uma entrada por titular da página, fix G13), leitura de um titular por id/e-mail incl. inativos (`GET /v1/admin/users/{id}` e `.../email/{email}`, `AdminUserResponseDTO` com `roles`, sem cache, audita `ADMIN_READ_USER` — ADR-016, fix G1/IDOR), consulta da trilha de auditoria LGPD por titular e feed geral (`GET .../audit-logs`, paginação com teto `MAX_AUDIT_PAGE_SIZE=100`), gestão de roles (`PATCH /v1/admin/users/{id}/roles` — payload sem `USER` ou role fora de `{USER,ADMIN}` → 400; auto-revogação de `ADMIN` do próprio ator, checada contra o estado persistido no Mongo → 409) e os 2 deletes administrativos absorvidos do `UserController` (`DELETE /v1/admin/users/{id}` soft-delete, `DELETE /v1/admin/users/del/{id}` hard-delete, ADR-013). Enforcement de `ROLE_ADMIN` é só downstream (`@PreAuthorize`); o gateway não ganha `hasRole()`.
- **Verificação de e-mail no cadastro (ADR-015):** `RegisterService` seta `emailVerified=false` no cadastro (antes: sempre `true`), mas **não dispara mais** `EmailVerificationService.issueVerificationEmail()` automaticamente — o cadastro só persiste o estado não-verificado; o envio do e-mail é sempre um ato explícito via reenvio (self ou admin). `issueVerificationEmail()` continua existindo, agora chamado só internamente por `doResend()`. Outbox na coleção `notificationOutbox` (token opaco, hash SHA-256 persistido, TTL 15 min); o disparo ao notification-service é via Feign **assíncrono** (`@Async`, executor `notificationExecutor`) com circuit breaker Resilience4j (`configs.notification-service`) + `NotificationClientFallbackFactory` — falha de envio nunca propaga ao cadastro/reenvio. Endpoint público: `GET /v1/users/verify-email?token=...` (confirma, idempotente, audita `EMAIL_VERIFIED`). Reenvio deixou de ser público/por-e-mail: `POST /v1/users/resend-verification` (self, `ROLE_USER`, titular via `userID` do JWT, no `UserController`) e `POST /v1/admin/users/{id}/resend-verification` (admin, `ROLE_ADMIN`, titular via `{id}`, no `AdminController`) delegam a `EmailVerificationService.resendByUserId(String)` (lança `DomainEntityNotFound`/404 se o id não existir; resposta `202`, reenvio não auditado) — desde a remoção do envio automático, esse é o **único** caminho pelo qual o e-mail de verificação é efetivamente enviado. `ResendRateLimitService` (Redis, por conta, default 3/h) complementa o rate limit por usuário/IP do gateway. **Varredura de retry (`OutboxRetryService`, emenda do ADR-015 — que originalmente descartou o poller):** `@Scheduled` a cada `retry-interval` (5m) reprocessa outbox `FAILED`/`PENDING`, porque um `FAILED` era beco sem saída — nada tocava o registro e, passada a carência de 24h, a conta ficava permanentemente inacessível. **Emite token NOVO, não reenvia o e-mail original:** só o `tokenHash` é persistido, então não há como remontar o link. Para quando: backoff (`retry-backoff`, 15m) não decorrido, titular inexistente, `emailVerified` diferente de `false` explícito, ou teto `retry-max-attempts` (5) — **contado sobre o número de registros** do par (titular, tipo), não sobre o campo `attempts`, porque cada retry cria um registro novo com `attempts=0` e um contador por registro nunca expiraria. Lock `SETNX` no Redis (`outbox_retry:lock`), **fail-CLOSED — o único do sistema**: cache/rate limit/revogação são fail-open porque Redis fora não pode barrar autenticação; aqui falhar aberto com N instâncias significaria N e-mails duplicados por ciclo.
- **Revogação ativa de token (ADR-017):** `TokenRevocationService` grava um **epoch de revogação** por usuário no Redis (`revoke:user:{userID}`, TTL `security.revocation.ttl` default 75m) em `AdminService.updateUserRoles`, `RegisterService.deactivateUser` e `.deleteUser` (self **e** admin), junto das evictions de cache. O resource server rejeita o token cujo `iat` precede o epoch via `RevocationTokenValidator` (somado aos validadores default no `JwtDecoder` de issuer **lazy** — não acopla o startup ao auth-server). Fail-open (erro de Redis não bloqueia), toggle `security.revocation.enabled`. Fonte única compartilhada com gateway e auth-server (`key-prefix` deve casar).
- **Consentimento LGPD no cadastro (ADR-012):** `UserRequestDTO.termsAccepted` (`@NotNull` + `@AssertTrue`, grupo `OnCreate` — obrigatório e `true` só no cadastro, ignorado no update); `RegisterService` grava `consentAcceptedAt` (timestamp) + `termsVersion` (`app.terms.version`/`TERMS_VERSION`, default `v1`) na coleção `users`. Campos nullable no entity (compat. com legados). Front: checkbox de aceite (links `/terms`/`/privacy`) que desabilita "Criar conta" até marcar.
- **Trilha de auditoria LGPD (ADR-011):** coleção `auditLogs` alimentada por `AuditService` registra *quem (ator) acessou/alterou/apagou (ação) qual dado de qual titular, quando* — **distinta** do log SLF4J operacional. Captura nos controllers (ator/alvo/ação inequívocos): mutações (REGISTER/UPDATE/SOFT_DELETE_ADMIN/HARD_DELETE_ADMIN/SOFT_DELETE_SELF/HARD_DELETE_SELF/ROLE_GRANT/ROLE_REVOKE/EMAIL_VERIFIED), leitura de credencial interna (`READ_INTERNAL_CREDENTIAL`, ator SYSTEM) e leitura administrativa de PII — por id/e-mail (`ADMIN_READ_USER`, ADR-016) e pela listagem paginada (`ADMIN_LIST_USERS`, fix G13, via `AuditService.recordBulkFromJwt`: **uma entrada por titular retornado**, `insert` em lote, página vazia não grava nada); `/me` e leitura do próprio dado **não** são auditados. O valor `READ_CROSS_SUBJECT` ficou `@Deprecated` (não mais emitido — as leituras por id/e-mail viraram ADMIN-only; mantido p/ registros históricos). `targetEmail` mascarado; `correlationId` = traceId B3 (o IP do cliente vive no log de borda do gateway). Escrita **assíncrona** (`AuditAsyncConfig`/`auditExecutor`, MDC propagado via `TaskDecorator`) e **isolada de falha** (erro de auditoria nunca derruba a operação). **Retenção (ADR-022):** `purgeAt` gravado por documento a partir de `app.audit.retention` (default **180d**) + índice TTL *expire-at* (`expireAfterSeconds=0`) — o mesmo padrão do `notificationOutbox`, e **não** TTL fixo no índice, que prenderia a retenção ao valor da criação e exigiria `collMod`. Entradas anteriores à mudança não têm o campo e o TTL do Mongo as ignora: o histórico antigo nunca é apagado. Índice `ts_idx` (`{timestamp:-1}`) para o **feed geral**, que é `findAll` ordenado por `timestamp` e não é servido pelo `target_ts_idx` (tem `targetUserId` no prefixo) — sem ele o sort vai para memória e o Mongo aborta a query aos 32 MB. Dívida: async = risco de perda em crash; a retenção é decisão **de conformidade** — se o prazo legal aplicável for maior, ajuste `AUDIT_LOG_RETENTION` antes de a primeira entrada expirar. A consulta da trilha agora existe via `AdminController` (`GET /v1/admin/audit-logs` e `.../users/{id}/audit-logs`, ADR-014) — fecha parcialmente a dívida "sem endpoint de consulta".
- **Campos scaffold no entity `User`:** `tenantIds` (reservado para multi-tenant futuro, sempre `null` — sem atribuição implementada). `emailVerified`/`emailVerifiedAt` deixaram de ser scaffold inerte — o fluxo de verificação (ADR-015) agora os alimenta de fato; `null` em legados continua tratado como verificado em toda leitura. Esses campos (`tenantIds`, `emailVerified`, `emailVerifiedAt`, `consentAcceptedAt`, `termsVersion`) são nullable no entity e expostos no `UserResponseDTO` — mas `name`, `email`, `passwordHash`, `registrationDate`, `roles` e `active` têm `@NotNull`. Nota: as constraints do entity são decorativas (não há `ValidatingMongoEventListener`); a validação efetiva é a do `UserRequestDTO`.

### notification-service (8095)

- **Tipo:** serviço stateless (sem MongoDB/Redis/PostgreSQL próprios), responsável pelo envio de e-mail de verificação de cadastro (ADR-015). Primeira responsabilidade de um bounded context de notificação, deliberadamente separado do user-service (SMTP é infraestrutura ortogonal ao domínio de identidade).
- **Endpoint:** `POST /internal/notifications/email-verification` — canal interno (`/internal/**`), protegido pelo mesmo shared secret `X-Internal-Token` do ADR-006, mas sem Spring Security: um `Filter` de servlet simples (`InternalTokenFilter` + `FilterRegistrationBean`) basta, pois não há outra rota autenticável no serviço. **Nunca exposto pelo gateway.**
- **Sem springdoc no classpath (ADR-021):** a dependência foi **removida do `pom.xml`**, e o controller não tem `@Tag`/`@Operation`. Antes, o YAML desligava só a `swagger-ui` e `/v3/api-docs` seguia publicando a especificação do canal interno — violação da invariante do ADR-006. Desligar por propriedade seria garantia condicional (o serviço importa a config com `optional:configserver:`); ausência de dependência é garantia de classpath. **Não reintroduzir.** O `/actuator/**` deixou de responder na 8095: vive na porta de management `8181`, não publicada (G14 fechado). Como o serviço não tem Spring Security, essa porta **é** o único controle — não a publique.
- **Envio:** `JavaMailSender` (SMTP configurável via env/secret; defaults de dev são placeholders sem credenciais reais — ver `docs/CONFIG.md`). O corpo do e-mail **não** informa prazo numérico: o TTL vive no user-service e o DTO não o carrega — cravar "15 minutos" faria o texto mentir ao mudar a env.
- **Consumidor:** só o user-service chama esta rota (Feign + circuit breaker Resilience4j + `NotificationClientFallbackFactory`, chamada disparada via `@Async`). Registrado no Eureka, config-server e Prometheus como os demais serviços de domínio.

### gateway (8081)

- **Base:** Spring Cloud Gateway (WebFlux/reativo, `spring-cloud-starter-gateway-server-webflux`). Único ponto de entrada externo — **nunca chame os serviços diretamente em produção**.
- **Cliente OAuth2 do BFF:** `oauth2Login` + `oauth2Client` (`gateway-client` confidencial) + resource server JWT. Guarda o token na sessão e o relaya downstream — o SPA nunca vê o JWT.
- **Rate limiting** via Redis (token bucket): LOW 2 req/s cap 5 (registro/IP), MED 5 req/s cap 10 (OAuth2/IP), HIGH 10 req/s cap 20 (autenticados/user). **IP não-falsificável (ADR-010):** `RateLimiterConfig`/`RateLimitLogFilter` resolvem o IP via `com.users.gateway.util.ClientIpResolver` — preferindo o header confiável `security.trusted-client-ip-header` (default `CF-Connecting-IP`), com fallback em `remoteAddress.getHostString()` (que sob `server.forward-headers-strategy=framework`, agora na base, reflete o XFF sanitizado pela borda; o `remoteAddress` vem _unresolved_, por isso `getHostString()` e não `getAddress()`). O `X-Forwarded-For` bruto não é mais lido — sob `cloudflared` (append) o leftmost é controlado pelo cliente.
- **Rotas em Java** (`GatewayRouter`, `RouteLocatorBuilder`). **`TokenRelay` é por rota** (na rota `user-service`), **não** via `default-filters` do yaml — a DSL Java não recebe default-filters. Rota pública pré-sessão `/v1/users/verify-email` (ADR-015) é explícita e precede a rota genérica `user-service`, com tier LOW por-IP (mesmo de `/v1/users/register`) e `permitAll()`. Rota `connect-logout` (`/connect/**` → `lb://authorization-server`, ADR-018): front-channel do RP-Initiated Logout, necessária porque sob hostname único o `end_session_endpoint` tem de existir na origem pública. Tier **MED por-IP** (não por-usuário: a request chega sem sessão — o `POST /logout` acabou de encerrá-la — e o `userKeyResolver` colapsaria todo mundo em `"anonymous"`), **sem `tokenRelay()`** (o `id_token_hint` viaja na query string) e no `permitAll()` do `SecurityConfig`. Rotas do front-channel de login (ADR-019), pelo mesmo racional de "sem sessão → MED/LOW por-IP, nunca por-usuário": `auth-login` (`/login` → `lb://authorization-server`) ganhou tier **MED por-IP** — antes não tinha rate limit algum, e sob borda pública é o formulário do IdP exposto a flood; e `auth-default-ui` (`/default-ui.css`, match exato) serve o CSS desse formulário em tier **LOW por-IP**, sem `tokenRelay()` e no `permitAll()` — sem ela o path cai no `try_files` do nginx e volta `text/html` com `nosniff`, deixando o formulário sem estilo. Como o `RedisRateLimiter` do SCG compõe a chave com `routeId` (`request_rate_limiter.{routeId.ip}`), essas rotas **não** compartilham balde com `/v1/users/register`, apesar do tier LOW comum. `/v1/users/resend-verification` deixou de ser pré-sessão: cai na rota genérica `user-service` (tokenRelay, tier HIGH por-usuário) como self-service autenticado; o reenvio administrativo (`/v1/admin/users/{id}/resend-verification`) cai na rota `admin-service` (tier MED).
- **CSRF** habilitado (`CookieServerCsrfTokenRepository`, cookie `XSRF-TOKEN`; `/v1/users/register` e `/login` isentos); **logout RP-initiated**.
- **Entry point híbrido (ADR-020):** `DelegatingServerAuthenticationEntryPoint` — **401** (não 302) para tudo, que é a premissa do BFF (o SPA é cliente JSON e decide quando logar), **exceto** `/swagger-ui/**` e `/swagger-ui.html`, que recebem **302** para `/oauth2/authorization/gateway-client` por serem navegação de browser. `/v3/api-docs/**` fica **fora** do redirect de propósito (é XHR: 302 para HTML faria o swagger-client parsear a tela de login como JSON).
- **Swagger atrás da sessão (ADR-020):** `/swagger-ui/**`, `/swagger-ui.html` e `/v3/api-docs/**` **não** estão no `permitAll()` — exigem sessão. Devolvê-los ao `permitAll()` reabre o vetor que publicou o `OAUTH_CLIENT_SECRET` no `swagger-initializer.js`. O bloco `springdoc.swagger-ui.oauth` do `gateway.yml` foi removido e **não deve voltar**: o "Try it out" autentica pelo cookie `SESSION` + `tokenRelay()`, sem botão *Authorize*.
- **Sessão WebFlux no Redis** via Spring Session (`@EnableRedisWebSession` — exige anotação explícita). Guarda `OAuth2AuthorizedClient` (com JWT) + `SecurityContext`. Cookie `SESSION`.
- **Tracing no edge reativo:** sendo WebFlux, o `traceId`/`spanId` no `logging.pattern.level` (MDC) só é populado com `spring.reactor.context-propagation: auto` (no `gateway.yml`) — sem isso o log do gateway sai com `traceId=` vazio e a correlação log↔Zipkin quebra na borda.
- **Revogação ativa de token na borda (ADR-017, defesa em profundidade):** `RevocationWebFilter` (`GlobalFilter`) inspeciona o access token guardado na sessão (o JWT relayado), lê o epoch de revogação por usuário no Redis (mesma chave do user-service) e responde **401** + invalida a sessão quando o token foi emitido antes da revogação. Como o BFF autentica por sessão (não por bearer), a checagem do resource server não pega o tráfego do SPA — daí o filtro. O user-service é a camada autoritativa; aqui o ganho é rejeição imediata na borda. Fail-open.
- **Outros:** filtros `CorrelationIdFilter` (semeia o `X-Correlation-ID` a partir do traceId B3 corrente, com fallback UUID — id único de correlação alinhado ao trace) e `RateLimitLogFilter`; load balancing via Eureka (`lb://`).

### login-interface (5173 dev / 80 Docker)

- **Stack:** React 19 + TypeScript + Vite + TailwindCSS 4.
- **BFF funcionando ponta a ponta** — registro/login/perfil/logout. Token nunca toca o browser; zero `localStorage`.
- **Por que BFF (e não SPA-com-PKCE):**
  - **Token nunca toca o browser** (fica na sessão do gateway; cookie `HttpOnly`+`Secure`+`SameSite`) → XSS não exfiltra JWT/refresh, eliminando de raiz o gap "JWT em `localStorage`".
  - É a **recomendação do IETF** (BCP OAuth para apps com backend).
  - **Reaproveita o backend** existente.
  - **Menos peça móvel no front:** sem PKCE, sem `/callback`, sem refresh manual.
  - **Alinha com a sessão no Redis.**
- **Mecânica:**
  - **Same-origin via proxy — e os dois proxies divergem (ADR-019).** No **nginx** (Docker/deploy) são os 9 paths listados na seção _Deploy via Cloudflare Tunnel_, **incluindo `/login`**: sob hostname único o formulário do IdP tem de existir na origem pública. No **Vite** (dev manual, `:5173`) são só 4 — `/v1/users`, `/oauth2`, `/login/oauth2`, `/logout` — porque em dev o browser vai **direto** ao `localhost:8082` para o front-channel (ver `OAUTH_AUTHORIZATION_URI` abaixo), então `/login` nunca precisa passar pelo proxy.
  - **`/login` pertence ao IdP, não ao SPA (ADR-019).** O `router.tsx` **não tem** rota `/login`: `<Login/>` mora em `/`, e `ProtectedLayout`/`useRegister` redirecionam para `/`. Recriar uma rota `/login` no React Router colide com o formulário do authorization-server sob hostname único — foi o Elo 3 do ADR-019.
  - **Sem token no front:** `apiAxios` com `withCredentials`, sem `Authorization: Bearer`; estado de auth derivado de `GET /v1/users/me` (200 vs 401).
  - **Hostname OAuth em Docker:** `OAUTH_AUTHORIZATION_URI` aponta para `localhost:8082` (front-channel/browser), enquanto `issuer-uri`/token/jwks ficam no hostname interno `authorization-server:8082`.
  - **CSRF** via `X-XSRF-TOKEN`.
  - **Logout:** form oculto `POST /logout` → `end_session_endpoint`.

---

## Convenções e Invariantes

Detalhe e racional em [docs/CONVENCOES.md](docs/CONVENCOES.md); decisões formais em [docs/adr/](docs/adr/). As invariantes a **preservar** (quebrá-las reintroduz bugs já resolvidos):

- **Separação rígida:** o authorization-server não acessa MongoDB — só via Feign para o user-service.
- **Canal interno isolado:** `/internal/users/email/{email}` fora do gateway e do Swagger, protegido por `X-Internal-Token` (ADR-006).
- **DELETE self-service no `UserController`:** soft-delete USER (`/remove/me`) e hard-delete USER (`/delete/me`) sobre a própria conta. As rotas administrativas de deleção de outro titular (soft/hard-delete ADMIN) foram removidas deste controller (ADR-013) e absorvidas pelo `AdminController` dedicado (`DELETE /v1/admin/users/{id}` e `.../del/{id}`, ADR-014).
- **BCrypt** custo 10; **roles fixas** `USER`/`ADMIN` (sem roles dinâmicas).
- **Cookies de sessão distintos:** gateway `SESSION`, auth-server `AUTHSESSION` — evita colisão no salto front-channel; cada serviço também usa `redisNamespace` próprio (`gateway:session` / `authserver:session`) isolando as sessões no Redis (ADR-007).
- **Spring Session explícito no Boot 4.0:** `@EnableRedisWebSession` (gateway) / `@EnableRedisHttpSession` (auth-server) — a autoconfig não dispara só pela dependência.
- **Config mutável em containers:** Redis Sentinel e MongoDB keyfile usam o padrão copy-to-`/tmp` + override de `entrypoint` (Mongo); não troque por mount `:ro` simples.
- **Revogação ativa de token (ADR-017):** o **epoch de revogação por usuário** (`revoke:user:{userID}` no Redis) é a fonte ÚNICA, com `key-prefix` **idêntico** nos três serviços (user-service grava; gateway/user-service/auth-server leem). Revogação **força re-autenticação** — não muta a sessão viva; o re-login re-deriva roles e aplica o gate de e-mail/`active` (ADR-015). Checagem é **fail-open** (outage de Redis não bloqueia a autenticação). Não troque por introspection por-request nem por denylist de `jti` (não cobre "revogar todos os tokens de um usuário").
- **Autenticação no Redis (ADR-008):** senha única `REDIS_PASSWORD` (fail-fast, sem default no compose) nos 6 nós. `requirepass` **e** `masterauth` nos **três** data nodes — sem `masterauth` no master atual, o ex-master não reintegra como réplica pós-failover (PSYNC NOAUTH). Sentinels com `requirepass` + `sentinel auth-pass mymaster` (injetados em runtime, fora do `sentinel.conf` versionado). Clientes Spring precisam das **duas** propriedades: `spring.data.redis.password` (data nodes) **e** `spring.data.redis.sentinel.password` (sentinels) — omitir a segunda causa NOAUTH lazy só em runtime (o CI, com Redis standalone sem senha, não pega).

---

## Qualidade e Verificação

A garantia de que o sistema permanece íntegro a cada mudança vem de testes + gate de cobertura + CI. Estratégia completa em [docs/TESTES.md](docs/TESTES.md) e [docs/LOGS.md](docs/LOGS.md).

- **Testes:** suíte multi-camada — unitários (Mockito/reativos), de controller (`@WebMvcTest`), de integração (Testcontainers: Mongo/Redis/Postgres + WireMock) e de front-end (Vitest+RTL+MSW). Diretrizes, comandos por módulo e armadilhas do stack em [docs/TESTES.md](docs/TESTES.md). notification-service é stateless — sem testes de integração próprios.
- **Gate de cobertura JaCoCo:** roda na fase `verify` (regra `check`, piso **70%** LINE/BUNDLE nos 4 módulos de domínio — user-service/auth-server/gateway/notification-service; config-server/discovery-server são report-only). Classes novas/alteradas: alvo **80%**. Ver [docs/TESTES.md § Cobertura (JaCoCo)](docs/TESTES.md).
- **Smoke-test da topologia de login ([ADR-023](docs/adr/ADR-023-smoke-test-automatizado-login-hostname-unico.md)):** `infra/smoke-test/login-topology-smoke-test.sh` é a **única** camada que exercita a cadeia real `nginx (interface) → gateway → authorization-server` — os Testcontainers nunca sobem o container `interface` e o dev local vai direto a `localhost:8082`, sem nginx e sem o CSRF do gateway. Sobe a topologia de deploy (menos o `cloudflared`) e valida 5 asserções HTTP: (a) `GET /login` traz o form do IdP e **não** o `index.html` do SPA, (b) `/default-ui.css` é `text/css`, (c) `POST /login` com `_csrf` real não é 403, (d) o `Location` de `/oauth2/authorize` é **exatamente** `${PUBLIC_ORIGIN}/login`, (e) a base do compose não publica `gateway`/`interface`. Detalhes em [docs/TESTES.md](docs/TESTES.md#smoke-test-da-topologia-de-login-adr-023). **Ao editar `login-interface/nginx.conf`, `GatewayRouter` ou os `SecurityConfig` de gateway/auth-server, revisite as asserções** — o script é o que impede aquela classe de bug de só aparecer em produção de novo. Três detalhes que parecem cosméticos e não são: o `X-Forwarded-For: 203.0.113.9` da asserção (d) é o probe do Elo 6 (sem um IP **público** ali, o IP `172.x` do próprio container de curl cai na faixa de `trusted-proxies` e a regressão passa); a comparação do `Location` é por **igualdade**, não prefixo (erro do endpoint de autorização redireciona para o próprio `redirect_uri`, que também começa com a origem pública); e o cookie `AUTHSESSION` é montado à mão porque `APP_COOKIE_SECURE=true` faz o curl recusar reenviá-lo sobre HTTP.
- **CI** (`.github/workflows/ci.yml`): a cada push/PR roda `mvn verify` por módulo (matrix `backend`, que dispara o gate) + `npm run coverage` no front (job `frontend`) + validação da topologia base (`compose-validate`) + o smoke-test da cadeia de login (`smoke-test-login`). A `main` exige todos os checks verdes para merge (branch protection) — detalhes na seção _Integração Contínua (CI)_ do [README.md](README.md).
- **Logs:** SLF4J parametrizado (`{}`), formato em pipe (`| [VERBO] | ação | campo: valor`), níveis INFO/WARN/ERROR/DEBUG convencionados, `traceId`/`spanId` via B3, PII mascarada (`LogUtils.maskEmail()`).

---

## Gaps de Segurança Conhecidos

Controles ativos e dívida aceita detalhados em [docs/SECURITY.md](docs/SECURITY.md). Gaps **ativos** (dívida consciente, não regredir os controles existentes): **SMTP placeholder** (bloqueante: sem provedor real o e-mail de verificação não sai e a conta de terceiro fica inacessível após o grace period — **não abrir para cadastro externo**), `/terms` e `/privacy` **linkadas mas inexistentes** (consentimento do ADR-012 colhido sobre texto ilegível — base legal frágil), segredos em **Docker secrets** mas ainda arquivos no host (gap 0.3 fechado **parcialmente** — sem secret manager/rotação) com resíduo do `mongodb-exporter` (distroless) lendo `MONGO_*` do `.env`, keyfile MongoDB de dev no repo, TLS de transporte Redis ausente (senha trafega em claro na rede interna Docker; portas Redis/Sentinel nunca publicadas no compose base), ACLs Redis por usuário ausentes (todos os clientes compartilham `REDIS_PASSWORD`). **Token de verificação de e-mail em URL (ADR-015):** risco aceito, mitigado por TTL de 15 min + uso único (status do outbox vira `CONFIRMED`/`SUPERSEDED`); resíduo fora do código deste repositório (spans Zipkin/logs de proxy externos podem capturar a query string). **Fechado:** chave JWK fora do repositório (gap 0.1 — gerada por `infra/jwk/gen-keys.sh`, secret no compose; [ADR-005](docs/adr/ADR-005-chave-jwk-persistente.md)), Grafana via secret (`__FILE`) — somado ao bind em **loopback** de Grafana/Prometheus/Zipkin (`127.0.0.1`, dev e deploy) e à ausência de regra de ingress para eles no túnel, que são o controle efetivo, já que nenhum dos três tem lockout/rate limit/MFA — e o gate de e-mail verificado no login — antes dormente, agora ativo de fato (ADR-015, mitigado por grace period de 24h), e o **IDOR de leitura de PII** (G1/`BLOCK-004`, era **ALTO**) — leitura por id/e-mail ADMIN-only no `AdminController` ([ADR-016](docs/adr/ADR-016-leitura-pii-restrita-admin.md)) **e listagem pública `GET /v1/users` removida** ([ADR-021](docs/adr/ADR-021-remocao-listagem-publica-usuarios.md)); atenção: o ADR-016 declarou o G1 fechado sem ver a listagem, que ficou seis semanas devolvendo PII de toda a base ativa a qualquer `USER` — **o critério de "fechado" verifica-se contra o código, nunca contra outro documento**. Fechados na mesma leva: o **canal interno do notification-service publicado em `/v3/api-docs`** (dependência springdoc removida) e o **lockout alimentado por indisponibilidade do user-service** (5 tentativas durante um outage bloqueavam a conta por 15 min). E a **ausência de revogação ativa de token** — agora há epoch de revogação por usuário no Redis (checado nos resource servers + bloqueio do refresh no auth-server; janela residual ≈ segundos, era indefinida via refresh; [ADR-017](docs/adr/ADR-017-revogacao-ativa-token.md)); **"sem TLS em prod"** — fechado com o named tunnel + domínio fixo (TLS da Cloudflare sobre origem **estável**, não mais curativo de URL efêmera); as **ingress rules do túnel** — antes estado não-versionado no painel, agora `infra/cloudflared/config.yml` versionado (túnel locally-managed; resíduo: o `CNAME` de `${PUBLIC_HOST}` segue sendo estado da zona Cloudflare); e os **headers HTTP de segurança** (G3) — CSP/HSTS/Referrer-Policy/Permissions-Policy emitidos pelo nginx do SPA, com `'unsafe-inline'` em `style-src` como dívida consciente (Tailwind) e CSP própria no `location /swagger-ui`; e o **G10 — portas do gateway/interface na base do compose** (era ALTO) — `ports:` movidos para `docker-compose.override.yml`, restaurando a premissa do ADR-010 ([ADR-019](docs/adr/ADR-019-correcao-elos-login-hostname-unico.md)); e o **G12 — `OAUTH_CLIENT_SECRET` servido publicamente** (era **ALTO**, exposição ativa) — `springdoc.swagger-ui.oauth.client-secret` fazia o springdoc emitir um `ui.initOAuth({...,"clientSecret":"…"})` literal dentro de `/swagger-ui/swagger-initializer.js`, servido a anônimos; bloco `oauth` removido inteiro (era supérfluo sob BFF), segredo **rotacionado** e **G11 — `/swagger-ui/*` e `/v3/api-docs/*` públicos** fechado junto, sem depender do Cloudflare Access ([ADR-020](docs/adr/ADR-020-swagger-atras-da-sessao.md)); e o **G13 — listagem administrativa não auditada** (era MÉDIO) — `GET /v1/admin/users` devolvia PII paginada de vários titulares sem rastro na trilha LGPD, sendo desde o ADR-021 a única listagem do sistema; agora emite `ADMIN_LIST_USERS` com **uma entrada por titular retornado** (não uma agregada por requisição: com `targetUserId` nulo a listagem não apareceria no histórico de titular algum, e é essa a pergunta que a trilha responde), ao custo assumido de até 100 entradas por página — custo que motivou a retenção de 180d do ADR-022, já que a dívida "sem TTL" do ADR-011 fora contraída quando cada operação gravava **uma** entrada; e o **G14 — `/actuator/**` sem guarda fora do gateway** (era MÉDIO) — o actuator foi para `management.server.port: 8181` nos quatro serviços de domínio (porta nunca publicada; Prometheus e healthchecks apontam para ela), e a porta não publicada é o **único** controle — `"/actuator/**"` **tem de continuar** no `permitAll()` dos SecurityConfig, porque a chain do contexto pai governa também a porta de management: removê-la devolve 401 (user-service) ou 302 (auth-server, com healthcheck em falso-positivo) na própria 8181; **atenção ao escopo**: o gap fora escrito nomeando só auth-server e notification-service, mas o `user-service` estava igual — a correção do escopo estava na memória desde 2026-08-04 e nunca chegara ao documento, variante do mesmo erro do G1. **Achados de auditoria ainda não ratificados** (a tratar, **não** dívida aceita — ver [docs/SECURITY.md § Gaps recém-identificados](docs/SECURITY.md)): `/v1/admin/**` sem 2FA/tier dedicado (MÉDIO), CORS curinga operacional + sessões concorrentes (BAIXO), scan transitivo de deps pendente. Ao fechar um gap ou introduzir dívida, atualize `docs/SECURITY.md` e `.claude/memory/decisions.md`.

---

## Orquestração de Agentes

Mudanças de domínio (feature/bugfix/hotfix/novo serviço/atualização de dependências) passam por
um time de **subagentes** (`.claude/agents/`) conduzido pelo thread principal — protocolo,
papéis e regras invioláveis em [docs/ORQUESTRACAO.md](docs/ORQUESTRACAO.md). Resumo do pipeline:
`product-manager → senso-critico → techlead → qa-tester → [security-reviewer] → senso-critico`
(o `security-reviewer` é condicional à superfície de segurança; o `dependency-steward`
conduz o workflow `dependency-update`; o `report-writer` fica fora do pipeline linear, chamado
isoladamente para relatórios de impacto/estado, sem aprovar ou reprovar nada). Estado persistente
em `.claude/memory/` (`context.json`,
`decisions.md`, `blockers.md`); workflows em `.claude/workflows/`. **Regras-chave:** nunca pule o
`senso-critico` em mudança de contrato de API, nem o `security-reviewer` quando a segurança é
tocada; mudança de contrato/schema **exige ADR**; após 2 rodadas de revisão sem aprovação, escale
ao humano. Skills invocáveis: `/suggest-tests`, `/check-compat`, `/security-scan`, `/new-adr`. Os
agentes também podem ser chamados isoladamente.
