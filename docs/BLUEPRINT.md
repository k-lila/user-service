# Blueprint — Infraestrutura Genérica vs. Domínio "Usuário"

> Extraído como documento próprio para fechar uma lacuna identificada em auditoria: o
> CLAUDE.md e os ADRs chamam o projeto de "v1 do blueprint de um sistema de usuários", mas
> até aqui essa generalidade era só intenção implícita — nenhum documento dizia
> explicitamente o que é infraestrutura reutilizável e o que é específico do domínio
> "usuário". Este documento formaliza essa distinção.

**Como usar:** se você (ou outro time) vai usar este repositório como ponto de partida para
um domínio diferente (ex.: catálogo de produtos, pedidos), comece pela seção (D). As seções
(A)/(B)/(C) são o catálogo de referência que sustenta esse roteiro, e a seção (E) diz em que
escala a coisa roda — o piso para subir e por onde cresce.

---

## (A) Infraestrutura genérica

Reutilizável **verbatim** (ou com troca trivial de tipos/nomes) para qualquer domínio — não
carrega conhecimento sobre "usuário".

### Resiliência Feign

| Arquivo | Por que é genérico |
|---|---|
| `authorization-server/.../clients/UserClientFallbackFactory.java` | Implementa `FallbackFactory<T>` do Resilience4j; a interface-alvo (`IUserClient`) é o único ponto acoplado ao domínio — o padrão (capturar a causa, decidir entre exceção imediata vs. fallback silencioso) é genérico. |
| `user-service/.../clients/NotificationClientFallbackFactory.java` | Mesmo padrão, aplicado a outro client Feign — confirma que a estrutura se replica trocando só o tipo. |
| `authorization-server/.../config/FeignConfig.java` | `RequestInterceptor` que injeta `X-Internal-Token` no header — não referencia usuário, só "canal interno autenticado". |

### Observabilidade

| Arquivo | Por que é genérico |
|---|---|
| `authorization-server/.../config/FeignTracingConfig.java`<br>`user-service/.../config/FeignTracingConfig.java` | Propaga o contexto de trace B3 via `Propagator` do Micrometer no `RequestTemplate` Feign. Resolve o problema de "span órfão" em qualquer chamada Feign, de qualquer serviço, para qualquer outro. |
| `gateway/.../filter/CorrelationIdFilter.java` | `GlobalFilter` que alinha `X-Correlation-ID` ao `traceId` B3 corrente (fallback UUID). Não depende de rota ou payload. |

### Canal interno / segurança transversal

| Arquivo | Por que é genérico |
|---|---|
| `user-service/.../config/InternalTokenFilter.java`<br>`notification-service/.../config/InternalTokenFilter.java` | Mesmo filtro (shared-secret em header) duplicado em dois serviços sem nenhuma lógica de usuário — é o padrão "canal `/internal/**` fechado por token", aplicável a qualquer rota interna futura. |
| `gateway/.../util/ClientIpResolver.java`<br>`authorization-server/.../util/ClientIpResolver.java` | Resolve IP confiável (header `CF-Connecting-IP` → fallback `remoteAddr`/`getHostString()`); usado hoje para rate limit e lockout, mas a função em si não sabe o que é "usuário" — apenas resolve um IP. |

### Gateway / BFF

| Arquivo | Por que é genérico |
|---|---|
| `gateway/.../config/RateLimiterConfig.java` | Define os 3 buckets (`RedisRateLimiter` LOW/MED/HIGH) por IP ou por sujeito autenticado — throttling puro, sem payload de domínio. |
| `gateway/.../config/CORSConfig.java` | Configuração CORS reativa parametrizada por env — nenhuma lógica de negócio. |
| `gateway/.../routing/GatewayRouter.java` | Tabela de rotas em DSL Java (`RouteLocatorBuilder`): tiers de rate limit, `tokenRelay()` por rota, rotas públicas pré-sessão. Os *paths* são do domínio, mas o padrão — roteamento declarativo com filtro por rota — é reaproveitável inteiro. |
| `gateway/.../filter/RateLimitLogFilter.java` | Loga a decisão do rate limiter com o IP resolvido; observabilidade de throttling, sem payload de domínio. |
| `gateway/.../filter/CorrelationIdFilter.java` | Semeia `X-Correlation-ID` a partir do traceId B3 (fallback UUID) — correlação de request pura. |

### Revogação ativa de token (ADR-017)

Mecanismo completo, genérico para qualquer sistema com JWT de vida curta + refresh:

| Arquivo | Por que é genérico |
|---|---|
| `user-service/.../services/TokenRevocationService.java` | Grava um **epoch de revogação por sujeito** no Redis (`revoke:user:{id}`, TTL configurável). Nada no mecanismo é específico de "usuário" — é um relógio por sujeito. Fail-open. |
| `user-service/.../config/RevocationTokenValidator.java` | `OAuth2TokenValidator` que rejeita o token cujo `iat` precede o epoch. Somado aos validadores default num `JwtDecoder` de issuer lazy. |
| `gateway/.../filter/RevocationWebFilter.java` | Mesma checagem na borda, sobre o token guardado na sessão do BFF (defesa em profundidade — o resource server não vê o tráfego autenticado por cookie). |
| `authorization-server/.../services/RevocationRefreshGuard.java` | Fecha o refresh: aborta a reemissão quando a revogação é mais recente que o refresh token. Sem ele o gateway renovaria o access token silenciosamente. |

> A invariante crítica é o `key-prefix` **idêntico** nos três serviços — é a única coisa que
> acopla as quatro classes.

### Sessão e OAuth2

| Arquivo | Por que é genérico | Ressalva |
|---|---|---|
| `authorization-server/.../config/SecurityConfig.java` | `@EnableRedisHttpSession` + cookie/namespace dedicados — padrão Spring Session, reaplicável a qualquer Authorization Server. | — |
| `authorization-server/.../config/OAuth2ClientConfig.java` | Seeding idempotente de `RegisteredClient` via JDBC — estrutura padrão do Spring Authorization Server, não fala sobre usuário. | — |
| `authorization-server/.../config/JWKConfig.java` | Carregamento do par RSA fixo via PEM (`kid` estável) é puro mecanismo de chave. | Os **claims** customizados no JWT (`userID`, `roles`, `permissions`, definidos em `TokenCustomizerConfig.java`, fora desta tabela) são específicos de domínio — ver seção (C). |

### Async / MDC

| Arquivo | Por que é genérico |
|---|---|
| `user-service/.../config/AuditAsyncConfig.java`<br>`user-service/.../config/NotificationAsyncConfig.java` | Mesmo padrão de `ThreadPoolTaskExecutor` + `TaskDecorator` que copia o MDC (traceId/spanId) para a thread assíncrona — aplicável a qualquer executor dedicado que precise preservar correlação de log, independente do que está sendo processado. |

### CI/CD e infraestrutura Docker

| Item | Por que é genérico |
|---|---|
| `.github/workflows/ci.yml` | Matrix Maven por módulo + gate JaCoCo + `compose-validate` — nenhum passo é específico de "usuário"; o pipeline serve qualquer projeto multi-módulo Java/Spring. |
| `docker-compose.yml` (Mongo replica set, Redis Sentinel, Postgres, Zipkin, Prometheus, Grafana) | A topologia de observabilidade/dados é infraestrutura de plataforma — o domínio só decide *quais* bancos usar, não a forma como eles são orquestrados. |
| Padrão Docker secrets (`spring.config.import=configtree:`, `*_FILE`, `__FILE`) | Mecanismo de injeção de segredo é agnóstico ao que o segredo protege. |

---

## (B) Específico do domínio "usuário"

Acoplado a conceitos de conta/identidade — portar para outro domínio exige reescrever, não
copiar.

### Modelo de domínio

| Arquivo | Por que é específico |
|---|---|
| `user-service/.../domain/User.java` | Campos fixos (`email`, `passwordHash`, `roles`, `active`, `emailVerified`...) sem parametrização — é a entidade central do domínio. |
| `user-service/.../domain/AuditLog.java`, `AuditAction.java` | Enum de ações (`REGISTER`, `EMAIL_VERIFIED`, `ROLE_GRANT`, `SOFT_DELETE_ADMIN`...) descreve eventos do ciclo de vida de uma conta. |
| `user-service/.../domain/NotificationOutbox.java` | Hardcoded para notificação de verificação de e-mail (`userId`, `NotificationType.EMAIL_VERIFICATION`). |

### Serviços de negócio

| Arquivo | Por que é específico |
|---|---|
| `RegisterService.java` | Orquestra cadastro/atualização/desativação de `User` — regras de roles default, consentimento LGPD, etc. |
| `EmailVerificationService.java` | Fluxo de confirmação/reenvio de e-mail, amarrado a `User` + `NotificationOutbox`. |
| `AuthenticationService.java` | Implementa `UserDetailsService`, mapeia `User` → `AuthDTO`; cache key `authByEmail` hardcoded. |
| `CacheService.java` | Gerencia eviction dos caches `usersById`/`usersByEmail`/`authByEmail`. |
| `NotificationDispatchService.java` | Método público é `dispatchEmailVerification(...)` — específico ao caso de uso, não genérico de "disparar notificação". |
| `AdminService` / `SearchService` (usados em `AdminController`/`UserController`) | Regras de RBAC e busca paginada de contas de usuário. |

### Controllers

| Arquivo | Por que é específico |
|---|---|
| `UserController.java` | Rotas `/v1/users/**` — registro, perfil, self-delete, reenvio de verificação. |
| `AdminController.java` | Rotas `/v1/admin/**` — listagem com inativos, auditoria LGPD, gestão de roles, deletes administrativos. |
| `InternalUserController.java` | Canal interno `/internal/users/email/{email}` (ADR-006) — expõe credencial + roles ao auth-server. Escondido do OpenAPI com `@Hidden`. |
| `notification-service/.../controller/NotificationController.java`<br>`.../services/EmailService.java`<br>`.../config/InternalTokenFilterConfig.java`<br>`.../exceptions/EmailSendFailedException.java` | Bounded context de notificação (ADR-015). O *transporte* (SMTP via `JavaMailSender`, guarda por shared secret) é genérico; o **conteúdo** — assunto e corpo do e-mail de verificação de cadastro — é do domínio. |

### DTOs, exceções e repositórios

| Item | Por que é específico |
|---|---|
| `dtos/` (`UserRequestDTO`, `UserResponseDTO`, `AuthDTO`, `AdminUserResponseDTO`, `AuditLogResponseDTO`, `UpdateRolesRequestDTO`) | Campos hardcoded ao formato de conta de usuário. |
| `exceptions/` (`EmailAlreadyRegisteredException`, `InvalidVerificationTokenException`, `SelfRoleRevocationException`, `DomainEntityNotFound`) | Semântica de regras de negócio de conta/identidade. |
| `repository/IUserRepository.java`, `INotificationOutboxRepository.java`, `IAuditLogRepository.java` | Spring Data amarrado às entidades do item anterior (`findByEmail`, etc.). |
| `exceptions/GlobalExceptionHandler.java` (user-service) | O *mapeamento* exceção→`ProblemDetail` é genérico (inclui o handler de 405 do ADR-021), mas os tipos tratados são do domínio. |
| `config/MongoConfig.java`, `config/WebConfig.java` | Infraestrutura de persistência/web do módulo. |
| `util/LogUtils.java` (user-service e authorization-server) | `maskEmail()` — mascaramento de PII em log. Genérico, **duplicado nos dois módulos** por não haver módulo comum. |

---

## (C) Zona cinzenta

Estrutura genérica por baixo, mas com semântica de domínio embutida na superfície pública.
Adaptar = manter a estrutura, trocar a semântica.

| Arquivo | Parte genérica (manter) | Parte específica (substituir) |
|---|---|---|
| `user-service/.../services/AuditService.java` | Escrita assíncrona isolada de falha, propagação de MDC, padrão "ator/alvo/ação/correlationId" | Assinaturas públicas (`recordRegistration`, `recordEmailVerified`, `recordFromJwt(... String targetUserId, String targetEmail)`) — para outro domínio, troque por `recordFromJwt(action, actor, targetEntityId, targetLabel)` genérico, ou crie um `AuditAction` próprio do novo domínio |
| `authorization-server/.../services/LoginAttemptService.java` | Contador de falhas no Redis por par de chaves com TTL fixo (padrão de lockout genérico) | Assume "conta identificada por e-mail" — para outra identidade (API key, telefone) troque a chave `sha256(emailLower\|ip)` pelo identificador correto |
| `user-service/.../config/CacheConfig.java` | `RedisCacheManager`, TTL/serialização configuráveis por cache-name | Nomes de cache (`usersById`, `usersByEmail`, `authByEmail`) e os tipos cacheados são do domínio usuário |
| `user-service/.../services/VerificationTokenService.java` (referenciado por `EmailVerificationService`) | Geração de token opaco (`SecureRandom` + hash SHA-256) e TTL/uso único — padrão genérico de token de uso único | Contexto fixo em "verificação de e-mail"; o mesmo padrão serviria para reset de senha ou outro fluxo de token efêmero |
| `user-service/.../services/ResendRateLimitService.java` | Contagem em janela fixa via Redis (mesmo padrão de `LoginAttemptService`) | Chave normalizada por e-mail e semântica "reenvio de verificação" |
| `authorization-server/.../config/JWKConfig.java` + `TokenCustomizerConfig.java` | Carregamento de chave RSA, emissão de JWT | Os claims customizados (`userID`, `roles`, `permissions`) descrevem o domínio usuário |

---

## (D) Roteiro de adaptação para outro domínio

1. **Clone a infraestrutura da seção (A) como está.** Gateway (BFF, rate limit, CORS),
   padrão Feign + fallback factory, observabilidade (B3/Zipkin/Prometheus/Grafana), Docker
   secrets, sessão Redis, pipeline de CI — nenhum desses precisa de alteração para mudar de
   domínio.
2. **Reescreva a seção (B) do zero** com as entidades, serviços, controllers, DTOs e
   repositórios do novo domínio. Use os arquivos listados como referência de "forma", não de
   "conteúdo" (ex.: a separação `Controller` público vs. `AdminController` administrativo é o
   padrão a seguir; os campos de `User` não são).
3. **Para cada item da seção (C), decida explicitamente o que muda.** Esses são os pontos
   onde é fácil copiar a parte errada (a semântica) e deixar passar a parte certa (a
   estrutura) — ou vice-versa. Trate cada linha da tabela como um checklist de adaptação.
4. **Revise as invariantes de segurança documentadas em
   [docs/CONVENCOES.md](CONVENCOES.md) e [docs/SECURITY.md](SECURITY.md)** — a maioria
   (canal interno isolado, cookies de sessão distintos, autenticação Redis) é herdada da
   seção (A) e continua valendo; as que dependem de "usuário" (lockout, gate de e-mail
   verificado) precisam ser repensadas para a identidade do novo domínio.

---

## (E) Eixo de escala por componente

As seções (A)–(D) catalogam o que o código é. Esta diz em que **escala** ele roda: qual o piso
para subir e por onde cada peça cresce. Decisão e racional completos em
[ADR-024](adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md).

O princípio que governa todos os eixos:

> **Encolher é remover nó; crescer é adicionar nó — nunca reconfigurar cliente.**

É por isso que o piso mínimo mantém o replica set `rs0` (com um membro) em vez de Mongo
standalone, e mantém o Sentinel (com um nó) em vez de falar direto com o Redis: a `MONGODB_URI` e
o `spring.data.redis.sentinel.*` dos clientes ficam idênticos do piso ao topo.

### Eixo 1 — Replicável (`--scale <serviço>=N`)

Sem estado local. Podem rodar em N cópias simultâneas na mesma máquina.

| Componente | O que torna possível |
|---|---|
| `gateway` | Sessão WebFlux no Redis (`@EnableRedisWebSession`); o `OAuth2AuthorizationRequest` do front-channel vive na sessão, então o callback pode cair em outra réplica. Rate limit no Redis, não em memória. |
| `user-service` | Cache Redis (`RedisCacheManager`, nunca Caffeine), `@Scheduled` do `OutboxRetryService` com lock `SETNX` **fail-closed** — sem ele, N réplicas = N e-mails por ciclo. |
| `authorization-server` | Estado OAuth em Postgres (`Jdbc*Service`), sessão no Redis, seed do `gateway-client` protegido por índice único (ADR-022), purga com o mesmo lock `SETNX`. |
| `notification-service` | Stateless por construção — só SMTP externo. |
| `config-server` | Serve o mesmo `classpath:/config` em qualquer instância. Entrou neste eixo no ADR-024 (era o par nomeado `config-server-1/2`). |
| `interface` (nginx do SPA) | Serve estático + proxy; o `cloudflared` resolve `interface:80` por DNS. |

**Pré-condições que já estão pagas** — e que quebram se alguém as remover: nenhum
`container_name` e nenhum `ports:` na base para esses serviços (o CI verifica); `instanceId` do
Eureka único por container (default `<containerId>:<app>:<porta>`); o nginx do SPA e o `config-lb`
resolvem por DNS com `resolver` + `proxy_pass` sobre variável, e não por `upstream` estático.

**Tetos:** `AUTH_DB_POOL_SIZE` × N réplicas tem de caber no `max_connections` do Postgres
(default 100); `MONGO_MAX_POOL_SIZE` × N na memória do nó Mongo. `--scale` **não** funciona junto
com `docker-compose.override.yml`, que publica portas fixas no host — listas de `ports:` são
concatenadas no merge, nunca removidas.

### Eixo 2 — Réplica nomeada (`--profile ha`)

Cada nó tem identidade própria (hostname, porta, papel no quorum), então não são cópias
intercambiáveis. Crescem ligando o profile, não escalando.

| Componente | Piso | Com `--profile ha` | Por que é nomeado |
|---|---|---|---|
| `discovery-server` | 1 | 2 em peer replication | `EUREKA_HOSTNAME` e `EUREKA_PEER_URL` **recíprocos** — replicação de peer no Eureka é *push*; quebrar a reciprocidade faz um nó servir registry incompleto e o gateway responder 503 (ADR-024) |
| `mongo` | 1 membro de `rs0` | 3 membros | Membro de replica set tem host fixo na config do RS |
| `redis` | 1 (master) | 3 (1 master + 2 réplicas) | `--replicaof` aponta para host fixo |
| `redis-sentinel` | 1 | 3 (quorum 2) | Quorum exige contagem de nós conhecida |

Crescer o Mongo é **automático**: `infra/mongo/rs-reconcile.sh` descobre os membros alcançáveis por
DNS e faz `rs.reconfig` aditivo. Encolher é **manual e deliberado** (`rs.remove`) — remover membro
por lookup que falhou derrubaria o quorum numa falha transitória de DNS.

### Eixo 3 — Singleton

| Componente | Consequência |
|---|---|
| `auth-postgres` | **SPOF do login.** Sem ele não há autenticação nova nem refresh. Réplica exige segunda máquina; no host único o remédio é backup/PITR. |
| `config-lb` | Ponto único na frente de um eixo replicável. Mantido de propósito: dá failover HTTP-aware que o DNS round-robin não dá, e os clientes sobem com `fail-fast: true`. |
| `cloudflared` | Funil do caminho público. |
| `zipkin`, `prometheus`, `grafana`, exporters | Observabilidade; `zipkin` com storage `mem` não sobrevive a restart. |

### O que a escala **não** resolve

Vale escrever porque a expectativa errada é o que decepciona: replicar não reduz a latência de uma
requisição isolada, não levanta o teto do rate limit (global no Redis — mais réplicas absorvem
mais picos, não servem mais por cliente) e não escala escrita (um primário Mongo, um Postgres). O
ganho real de N > 1 é **deploy sem downtime**, cauda de latência sob GC e isolamento de falha de
processo.

### Ao adaptar para outro domínio

O eixo 1 é herdado inteiro **desde que o novo domínio não introduza estado local**. Se você
acrescentar cache em memória, agendamento sem lock distribuído, ou um `CommandLineRunner` de
bootstrap que grave, tirou o serviço do eixo replicável sem perceber. A checagem é direta:
`ConcurrentHashMap`/`Caffeine`/`static Map` em bean de serviço, `@Scheduled` sem lock, e
`@PostConstruct` que escreva no banco.

---

## Nota de dívida

Até a criação deste documento, a distinção entre infraestrutura genérica e código específico
de usuário era apenas implícita — inferível lendo o código, mas não registrada em lugar
algum. Isso foi identificado em auditoria de maturidade do projeto como o maior gap entre o
discurso ("blueprint reutilizável") e a realidade ("user-service bem arquitetado"). Este
catálogo deve ser atualizado sempre que uma nova classe for adicionada às camadas de
infraestrutura ou de domínio, para não voltar a divergir do código.
