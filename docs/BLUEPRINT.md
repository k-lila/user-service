# Blueprint — Infraestrutura Genérica vs. Domínio "Usuário"

> Extraído como documento próprio para fechar uma lacuna identificada em auditoria: o
> CLAUDE.md e os ADRs chamam o projeto de "v1 do blueprint de um sistema de usuários", mas
> até aqui essa generalidade era só intenção implícita — nenhum documento dizia
> explicitamente o que é infraestrutura reutilizável e o que é específico do domínio
> "usuário". Este documento formaliza essa distinção.

**Como usar:** se você (ou outro time) vai usar este repositório como ponto de partida para
um domínio diferente (ex.: catálogo de produtos, pedidos), comece pela seção (D). As seções
(A)/(B)/(C) são o catálogo de referência que sustenta esse roteiro.

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

### DTOs, exceções e repositórios

| Item | Por que é específico |
|---|---|
| `dtos/` (`UserRequestDTO`, `UserResponseDTO`, `AuthDTO`, `AdminUserResponseDTO`, `AuditLogResponseDTO`, `UpdateRolesRequestDTO`) | Campos hardcoded ao formato de conta de usuário. |
| `exceptions/` (`EmailAlreadyRegisteredException`, `InvalidVerificationTokenException`, `SelfRoleRevocationException`, `DomainEntityNotFound`) | Semântica de regras de negócio de conta/identidade. |
| `repository/IUserRepository.java`, `INotificationOutboxRepository.java`, `IAuditLogRepository.java` | Spring Data amarrado às entidades do item anterior (`findByEmail`, etc.). |

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

## Nota de dívida

Até a criação deste documento, a distinção entre infraestrutura genérica e código específico
de usuário era apenas implícita — inferível lendo o código, mas não registrada em lugar
algum. Isso foi identificado em auditoria de maturidade do projeto como o maior gap entre o
discurso ("blueprint reutilizável") e a realidade ("user-service bem arquitetado"). Este
catálogo deve ser atualizado sempre que uma nova classe for adicionada às camadas de
infraestrutura ou de domínio, para não voltar a divergir do código.
