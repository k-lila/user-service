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

## Como usar este documento

Este arquivo é um **mapa**, não um manual. Ele diz o que o sistema é, onde cada coisa mora e
quais invariantes não podem ser quebradas — o **racional** de cada decisão vive nos ADRs e nos
documentos temáticos.

> **Regra de manutenção:** ao aprender algo novo sobre uma decisão, escreva no ADR ou no doc
> temático e **linke daqui**. Não anexe o parágrafo a este arquivo. Ele já chegou a 67 KB, com
> uma única linha de 6.071 caracteres, por acúmulo de cláusulas "não regrida isto" — e é o
> arquivo carregado em toda sessão e todo subagente. **Nenhuma linha deve passar de ~200
> caracteres.**

---

## Visão Geral do Projeto

Sistema de microsserviços em Java + Spring para gerenciamento de usuários — a **v1 do blueprint
de um sistema de usuários**, funcional e em evolução ativa. Autenticação, registro, perfil e
controle de acesso funcionam **ponta a ponta**. O cuidado permanente é que cada nova
implementação seja **segura e compatível** com o que já existe: invariantes de design, contratos
entre serviços e controles de segurança preservados, com testes, gate de cobertura e
observabilidade ativos.

O front-end React (`login-interface`) usa o padrão **BFF**: o gateway é o cliente OAuth2, o SPA
usa sessão por cookie e **não** manuseia JWT.

**Mapa de documentos:**

| Documento | Conteúdo |
| --- | --- |
| [README.md](README.md) | pré-requisitos e execução (público humano) |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | camadas de cada serviço, fluxos ponta a ponta, contratos, árvore de arquivos anotada |
| [docs/SERVICOS.md](docs/SERVICOS.md) | referência da API: endpoints, schema MongoDB, cache |
| [docs/CONFIG.md](docs/CONFIG.md) | variáveis de ambiente, Docker secrets, limites de recursos |
| [docs/CONVENCOES.md](docs/CONVENCOES.md) | convenções e invariantes de design |
| [docs/TESTES.md](docs/TESTES.md) | estratégia de testes, cobertura, smoke-test |
| [docs/LOGS.md](docs/LOGS.md) | estratégia de logs |
| [docs/OBSERVABILIDADE.md](docs/OBSERVABILIDADE.md) | tracing, métricas, dashboards e seus thresholds |
| [docs/SECURITY.md](docs/SECURITY.md) | controles ativos e gaps de segurança conhecidos |
| [docs/ORQUESTRACAO.md](docs/ORQUESTRACAO.md) | sistema de orquestração de agentes |
| [docs/BLUEPRINT.md](docs/BLUEPRINT.md) | infraestrutura genérica vs. código específico do domínio usuário |
| [docs/adr/](docs/adr/) | Architecture Decision Records (ADR-001…026; template em `TEMPLATE.md`) |

ADRs são criados pelo `techlead` em mudanças de contrato/schema. Catálogo completo, em ordem, no
próprio diretório.

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
        │       ├── user-service via Feign (circuit breaker Resilience4j + fallback factory)
        │       ├── PostgreSQL (auth-postgres :5432 — estado OAuth)
        │       └── Redis Sentinel (sessão de login/consent)
        │
        ├── user-service :8090
        │       ├── MongoDB replica set rs0 (persistência)
        │       ├── Redis Sentinel (cache + rate limiting)
        │       └── notification-service via Feign assíncrono (circuit breaker + fallback)
        │
        ├── notification-service :8095  (stateless; SMTP; nunca exposto pelo gateway)
        │
        ├── discovery-server-1 :9091 [· discovery-server-2 :9092 no --profile ha]
        ├── config-lb :8888 → config-server (replicável por --scale, resolvido por DNS)
        ├── zipkin :9411 · prometheus :9090 · grafana :3000
```

**Tecnologias:** Java 21 · Spring Boot 4.0.x · Spring Cloud 2025.1.0 · Maven (POM pai na raiz) ·
MongoDB · PostgreSQL · Redis · React 19 + TypeScript + Vite + TailwindCSS 4 · Docker Compose.

Fluxo de autenticação OAuth2/BFF em 10 passos, claims customizados do JWT e contratos entre
serviços: [docs/ARQUITETURA.md § 1 e § 6.1](docs/ARQUITETURA.md).

---

## Desenvolvimento local

### Subir tudo com Docker

**Pré-requisito obrigatório (uma vez):** a base é **secrets-native** ([ADR-009](docs/adr/ADR-009-base-secrets-native-docker-secrets.md)) — sem `./secrets/` o `up` **falha**.

```bash
infra/secrets/gen-secrets.sh   # defaults de DEV; em prod exporte cada segredo com valor forte
docker compose up -d --build
```

O que saber antes de mexer no compose — detalhe em [docs/CONFIG.md](docs/CONFIG.md) e
[ADR-024](docs/adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md):

- **Piso mínimo por default:** o `up` sem argumento sobe **19 serviços**, com Mongo em replica set
  `rs0` de um membro e Redis com um nó e um Sentinel. Degenerado, não standalone — é o que mantém
  a config do cliente **idêntica** do piso ao topo. Crescer: `--profile ha` (26 serviços) e
  `--scale <svc>=N`.
- **`--scale` exige `-f docker-compose.yml` explícito** em dev: o override publica portas fixas e
  a 2ª réplica falha no bind. Listas de `ports:` são concatenadas no merge, nunca removidas.
- **Nenhum serviço fora do profile `ha` pode declarar `depends_on` para um dentro dele.**
- **O piso mínimo não é HA** (sem failover de Mongo/Redis).
- A **base não publica porta nenhuma** (prod-safe, G10/[ADR-019](docs/adr/ADR-019-correcao-elos-login-hostname-unico.md)). Quem publica é o `docker-compose.override.yml` (dev) e o
  `docker-compose.deploy.yml` (observabilidade em `127.0.0.1`).
- **Build:** um único `infra/docker/Dockerfile.jvm` para os seis módulos, parametrizado por
  `ARG MODULE`, com **contexto de raiz** (o `<parent>` dos filhos precisa estar no contexto). O
  `.dockerignore` é **fail-closed** — se você readmitir caminhos ali, confira que `secrets/` e
  `.env` continuam fora.

**Deploy via Cloudflare Tunnel:** named tunnel com domínio fixo, modo locally-managed, topologia
de **hostname único** — o túnel entrega em `interface:80` (nginx do SPA), que faz proxy same-origin
de 9 paths ao gateway. Nem gateway nem auth-server ficam alcançáveis de fora. Roteiro de subida em
[README § 2b](README.md); ingress versionadas em `infra/cloudflared/config.yml`; racional em
[docs/SECURITY.md](docs/SECURITY.md) e ADR-018/019/020.

### Ordem manual (sem Docker)

1. config-server → 2. discovery-server → 3. authorization-server → 4. user-service → 5. gateway
(`mvn -pl <módulo> spring-boot:run` a partir da raiz) → 6. login-interface (`npm run dev`).

Em dev manual do BFF, exporte
`OAUTH_REDIRECT_URI=http://localhost:5173/login/oauth2/code/gateway-client`.

### URLs de acesso

| Serviço       | URL                                         |
| ------------- | ------------------------------------------- |
| Gateway / API | http://localhost:8081                       |
| Swagger UI    | http://localhost:8081/swagger-ui/index.html |
| Eureka        | http://localhost:9091                       |
| Zipkin        | http://localhost:9411 🔒                     |
| Prometheus    | http://localhost:9090 🔒                     |
| Grafana       | http://localhost:3000 🔒                     |
| Front-end     | http://localhost:5173                       |

🔒 **Só a partir da própria máquina.** As três portas de observabilidade são publicadas presas ao
loopback (`127.0.0.1:PORTA:PORTA`), em dev e no deploy, e não têm regra de ingress no túnel.
Nenhuma tem lockout, rate limit ou MFA — Prometheus e Zipkin não têm autenticação alguma. Esse
bind **é** o controle de acesso: republicar sem o IP devolve o acesso à LAN inteira.

---

## Serviços

> Referência da API em [docs/SERVICOS.md](docs/SERVICOS.md); estrutura interna em
> [docs/ARQUITETURA.md](docs/ARQUITETURA.md). Aqui ficam só o papel de cada serviço e as
> invariantes cuja quebra reintroduz bug já resolvido.

### config-server (8888)

Config centralizada via `classpath:/config`; os demais importam com `spring.config.import`.
**Deve subir primeiro.** Protegido por HTTP Basic (`/actuator/health` aberto p/ healthcheck).

- Serviço **único** replicável por `--scale`, atrás do `config-lb` (nginx). Não é mais o par
  nomeado `config-server-1/2`.
- **Não volte ao bloco `upstream` estático no `config-lb`:** ele resolve os nomes no start e faz o
  nginx recusar iniciar se um não resolver — foi o que tornava o piso mínimo impossível. Use
  `resolver 127.0.0.11` + `proxy_pass` sobre variável.

### discovery-server (9091 / 9092)

Netflix Eureka em peer replication; o 2º nó vive no profile `ha`. `EUREKA_URI` lista ambas as
instâncias (CSV).

### authorization-server (8082)

OAuth2 Authorization Server: authorization_code + PKCE obrigatório + refresh_token. Estado OAuth
em PostgreSQL; sessão no Redis (cookie **`AUTHSESSION`**); credenciais via Feign ao user-service.

Invariantes a preservar (racional nos ADRs indicados):

- **Não acessa MongoDB** — só via Feign para o user-service.
- **Chave JWK persistente** com `kid` estável, carregada de PEM. Nunca versionada ([ADR-005](docs/adr/ADR-005-chave-jwk-persistente.md)).
- **Seed do `gateway-client` é check-then-act**; quem impede duplicata é o **índice único** sobre
  `client_id` ([ADR-022](docs/adr/ADR-022-higiene-estado-persistente.md)).
- **Purga do estado OAuth** usa o `GREATEST` das seis colunas de expiração, não uma delas — filtrar
  por `access_token_expires_at` deslogaria usuário ativo (ADR-022).
- **Fallback Feign distingue 404 de indisponibilidade** ([ADR-021](docs/adr/ADR-021-remocao-listagem-publica-usuarios.md)): 404 é negócio e **conta** no lockout;
  500/503/timeout/circuito aberto **não** contam. Nunca use `instanceof FeignException` genérico
  nesse teste. Complemento obrigatório: `ignoreExceptions: [feign.FeignException$NotFound]`.
- **Lockout** por par (conta, IP) no Redis, com IP do header confiável ([ADR-010](docs/adr/ADR-010-resolucao-ip-cliente-confiavel.md)). `ClientIpResolver`
  é a fonte ÚNICA.
- **Gate de e-mail verificado** no login, com carência de 24h ([ADR-015](docs/adr/ADR-015-verificacao-email-cadastro.md)).
- **Re-derivação do estado do titular na emissão** ([ADR-025](docs/adr/ADR-025-revalidacao-estado-emissao.md)) — o filtro tem meia dúzia de
  detalhes que parecem cosméticos e não são (posição na chain, matcher derivado das settings, não
  ser `@Component`, recorte de authorities por prefixo, nunca re-derivar via `AuthenticationManager`).
  **Leia o ADR-025 antes de tocar nesse filtro.**

### user-service (8090)

Domínio central: CRUD de usuários (MongoDB, coleção `users`) + cache Redis. Três controllers:
`UserController` (público, **só o próprio titular**), `InternalUserController` (canal interno) e
`AdminController` (`/v1/admin/**`, todo método `@PreAuthorize("hasRole('ADMIN')")`).

- **Leitura de PII de terceiro é ADMIN-only** ([ADR-016](docs/adr/ADR-016-leitura-pii-restrita-admin.md)) e a listagem pública foi **removida**
  (ADR-021). Não reintroduza rota que devolva dado de terceiro sob `ROLE_USER`.
- **`GlobalExceptionHandler` precisa de handler explícito** para toda exceção que o Spring
  traduziria sozinho (405, `NoResourceFoundException`→404): o advice não estende
  `ResponseEntityExceptionHandler`, então sem handler o catch-all devolve 500.
- **Verificação de e-mail** com outbox e retry ([ADR-015](docs/adr/ADR-015-verificacao-email-cadastro.md)); o retry emite token **novo** (só o hash é
  persistido) e o teto de tentativas conta **registros**, não o campo `attempts`.
- **`OutboxRetryService` é o único lock fail-CLOSED do sistema** — os demais (cache, rate limit,
  revogação) são fail-open porque Redis fora não pode barrar autenticação.
- **Trilha de auditoria LGPD** ([ADR-011](docs/adr/ADR-011-trilha-auditoria-dado-pessoal.md)), assíncrona e isolada de falha, com retenção de 180d por
  `purgeAt` + índice TTL *expire-at* (ADR-022). `ADMIN_LIST_USERS` grava **uma entrada por titular**.
- **Revogação ativa de token** ([ADR-017](docs/adr/ADR-017-revogacao-ativa-token.md) + [ADR-026](docs/adr/ADR-026-revogacao-troca-senha-email.md)): epoch por usuário no Redis, gravado em
  mudança de role, desativação, hard-delete e **troca de senha ou e-mail**.
- `tenantIds` é scaffold (sempre `null`). As constraints do entity são **decorativas** — não há
  `ValidatingMongoEventListener`; a validação efetiva é a do `UserRequestDTO`.

### notification-service (8095)

Stateless; envia o e-mail de verificação via `JavaMailSender`. Endpoint único
`POST /internal/notifications/email-verification`, protegido pelo shared secret `X-Internal-Token`
por um `Filter` de servlet simples (não há Spring Security aqui).

- **Sem springdoc no classpath, e não é por configuração** (ADR-021): a dependência foi removida do
  `pom.xml` e **não está no `<dependencies>` do POM pai** — só o pin de versão em
  `dependencyManagement`. Desligar por propriedade seria garantia condicional; ausência de
  dependência é garantia de classpath. **Não reintroduzir.**
- O actuator vive na porta de management **8181**, não publicada. Como não há Spring Security
  aqui, essa porta **é** o único controle — não a publique.

### gateway (8081)

Spring Cloud Gateway (WebFlux). Único ponto de entrada externo — **nunca chame os serviços
diretamente em produção**. Cliente OAuth2 do BFF, resource server JWT, sessão no Redis (cookie
`SESSION`), CSRF habilitado, rate limiting em 3 tiers.

- **Rotas em Java** (`GatewayRouter`); **`TokenRelay` é por rota**, não via `default-filters` — a
  DSL Java não recebe default-filters.
- **Rota sem sessão usa tier por IP, nunca por usuário** — o `userKeyResolver` colapsaria todo
  mundo em `"anonymous"`. Vale para `/connect/**`, `/login` e `/default-ui.css`.
- **Swagger exige sessão** ([ADR-020](docs/adr/ADR-020-swagger-atras-da-sessao.md)). Devolver `/swagger-ui/**` ou `/v3/api-docs/**` ao
  `permitAll()` reabre o vetor que publicou o `OAUTH_CLIENT_SECRET`. O bloco
  `springdoc.swagger-ui.oauth` foi removido e **não deve voltar**.
- **Entry point híbrido:** 401 para tudo (premissa do BFF), 302 só para `/swagger-ui/**`.
- **`spring.reactor.context-propagation: auto`** é o que popula o MDC no edge reativo — sem isso o
  log do gateway sai com `traceId=` vazio.
- **`RevocationTokenReader` nunca é bean de `ReactiveJwtDecoder`** — declará-lo faria o resource
  server aceitar bearer expirado, e o mock dos testes esconderia a regressão. Ver a invariante
  completa em [docs/ARQUITETURA.md § 4.3](docs/ARQUITETURA.md).

### login-interface (5173 dev / 80 Docker)

React 19 + TypeScript + Vite + TailwindCSS 4. BFF ponta a ponta: token nunca toca o browser, zero
`localStorage`. Estado de auth derivado de `GET /v1/users/me` (200 vs 401).

- **`/login` pertence ao IdP, não ao SPA** ([ADR-019](docs/adr/ADR-019-correcao-elos-login-hostname-unico.md)): o `router.tsx` **não tem** rota `/login`.
  Recriá-la colide com o formulário do authorization-server sob hostname único.
- **Os dois proxies divergem de propósito:** nginx (Docker/deploy) encaminha 9 paths, incluindo
  `/login`; Vite (dev manual) encaminha 4, porque em dev o browser vai direto ao `localhost:8082`.

---

## Convenções e Invariantes

Detalhe e racional em [docs/CONVENCOES.md](docs/CONVENCOES.md); decisões formais em
[docs/adr/](docs/adr/). Quebrar qualquer uma reintroduz bug já resolvido:

- **Separação rígida** — o authorization-server não acessa MongoDB.
- **Canal interno isolado** — `/internal/**` fora do gateway e do Swagger, protegido por
  `X-Internal-Token` ([ADR-006](docs/adr/ADR-006-canal-interno-isolado.md)).
- **DELETE com semânticas distintas** — soft e hard, self-service no `UserController`,
  administrativo no `AdminController` (ADR-013/014).
- **BCrypt custo 10; roles fixas** `USER`/`ADMIN`.
- **Cookies de sessão distintos** — `SESSION` (gateway) e `AUTHSESSION` (auth-server), com
  `redisNamespace` próprio ([ADR-007](docs/adr/ADR-007-sessao-redis-cookies-distintos.md)).
- **Spring Session explícito no Boot 4.0** — `@EnableRedisWebSession` / `@EnableRedisHttpSession`.
- **Config mutável em containers** — padrão copy-to-`/tmp`; não troque por mount `:ro` simples.
- **Epoch de revogação é fonte ÚNICA**, com `key-prefix` idêntico nos três serviços. Fail-open.
  Não troque por introspection por-request nem por denylist de `jti`.
- **As sete cópias do estado de autorização** — introduzir uma **oitava** exige declarar seu
  mecanismo de invalidação na tabela de `docs/CONVENCOES.md`, **no mesmo commit**.
- **Autenticação no Redis** ([ADR-008](docs/adr/ADR-008-autenticacao-redis-sentinel.md)) — clientes Spring precisam das **duas** propriedades
  (`spring.data.redis.password` **e** `...sentinel.password`); omitir a segunda dá NOAUTH lazy só
  em runtime, e o CI não pega.
- **Sem módulo `common`** — a duplicação de `ClientIpResolver`, `LogUtils`, `FeignTracingConfig` e
  `InternalTokenFilter` entre módulos é **decisão mantida**, não pendência. O POM pai centraliza
  versões e build, não código. Alterou uma cópia, verifique a gêmea: o build não acusa.

---

## Qualidade e Verificação

Estratégia completa em [docs/TESTES.md](docs/TESTES.md) e [docs/LOGS.md](docs/LOGS.md).

- **Testes:** unitários (Mockito/reativos), de controller (`@WebMvcTest`), de integração
  (Testcontainers: Mongo/Redis/Postgres + WireMock) e de front (Vitest+RTL+MSW).
- **Gate JaCoCo:** fase `verify`, piso **70%** LINE/BUNDLE nos 4 módulos de domínio; config-server
  e discovery-server são report-only e **desligam o `check` herdado** com `<phase>none</phase>`.
  Classes novas/alteradas: alvo 80% (meta, não gate).
- **Smoke-test da topologia de login** ([ADR-023](docs/adr/ADR-023-smoke-test-automatizado-login-hostname-unico.md)): única camada que exercita a cadeia real
  `nginx → gateway → authorization-server`. **Ao editar `login-interface/nginx.conf`,
  `GatewayRouter` ou os `SecurityConfig` de gateway/auth-server, revisite as asserções.**
- **CI:** `mvn -B -pl <módulo> -am verify` por módulo (matrix `backend`) + `npm run coverage` +
  `compose-validate` + `smoke-test-login`. A matriz existe para paralelizar; **não renomeie o job
  nem os módulos** — a branch protection referencia `backend (<módulo>)` literalmente.
- **Logs:** SLF4J parametrizado, formato em pipe, `traceId`/`spanId` via B3, PII mascarada.

---

## Gaps de Segurança Conhecidos

Inventário completo, com mitigação e caminho de saída, em [docs/SECURITY.md](docs/SECURITY.md).
Gaps **ativos** (dívida consciente — não regrida os controles existentes):

| Gap | Nota |
| --- | --- |
| **SMTP placeholder** | **Bloqueante:** sem provedor real o e-mail de verificação não sai e a conta fica inacessível após a carência. **Não abrir para cadastro externo.** |
| `/terms` e `/privacy` linkadas mas inexistentes | Consentimento do ADR-012 colhido sobre texto ilegível — base legal frágil |
| Segredos em Docker secrets, mas arquivos no host | Sem secret manager/rotação; resíduo do `mongodb-exporter` lendo `MONGO_*` do `.env` |
| Keyfile MongoDB de dev no repo | — |
| TLS de transporte Redis ausente | Senha em claro na rede interna Docker; portas nunca publicadas na base |
| ACLs Redis por usuário ausentes | Todos os clientes compartilham `REDIS_PASSWORD` |
| Token de verificação de e-mail em URL | Risco aceito (ADR-015): TTL 15 min + uso único |
| `/v1/admin/**` sem 2FA/tier dedicado | Achado de auditoria, **não ratificado** |
| CORS curinga operacional + sessões concorrentes | Achado de auditoria (BAIXO), **não ratificado** |
| Scan transitivo de dependências pendente | Achado de auditoria, **não ratificado** |

Gaps **fechados** (G1, G3, G10–G15, chave JWK, TLS em prod, revogação ativa, ingress versionadas)
estão detalhados em `docs/SECURITY.md`, cada um com o que exatamente foi feito.

> **Ao fechar um gap ou introduzir dívida:** atualize `docs/SECURITY.md` e
> `.claude/memory/decisions.md`. E lembre da regra que governa as outras: **o critério de "fechado"
> verifica-se contra o código, nunca contra outro documento** — o G1 e o G14 foram declarados
> fechados com base em documento e não estavam.

---

## Orquestração de Agentes

Mudanças de domínio passam por um time de **subagentes** (`.claude/agents/`) conduzido pelo thread
principal — protocolo, papéis e regras invioláveis em [docs/ORQUESTRACAO.md](docs/ORQUESTRACAO.md).

Pipeline: `product-manager → senso-critico → techlead → qa-tester → [security-reviewer] →
senso-critico`. O `security-reviewer` é condicional à superfície de segurança; o
`dependency-steward` conduz o workflow `dependency-update`; o `report-writer` fica fora do
pipeline linear.

**Regras-chave:** nunca pule o `senso-critico` em mudança de contrato de API, nem o
`security-reviewer` quando a segurança é tocada; mudança de contrato/schema **exige ADR**; após 2
rodadas de revisão sem aprovação, escale ao humano.

Estado persistente em `.claude/memory/`; workflows em `.claude/workflows/`. Skills invocáveis:
`/suggest-tests`, `/check-compat`, `/security-scan`, `/new-adr`.
