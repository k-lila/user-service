# Referência da API (user-service)

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Endpoints expostos via gateway](#endpoints-expostos-via-gateway)
- [Exemplos de payload](#exemplos-de-payload)
- [Claims do JWT](#claims-do-jwt)
- [Schema MongoDB (coleção `users`)](#schema-mongodb-coleção-users)
- [Schema MongoDB (coleção `auditLogs`)](#schema-mongodb-coleção-auditlogs)
- [Estratégia de cache (Redis)](#estratégia-de-cache-redis)
- [Formato de erros (RFC 7807 / ProblemDetail)](#formato-de-erros-rfc-7807--problemdetail)

## Endpoints expostos via gateway

| Método | Path                    | Auth       | Rate Limit      |
| ------ | ----------------------- | ---------- | --------------- |
| POST   | /v1/users/register      | Nenhuma    | 2 req/s (IP)    |
| GET    | /v1/users/verify-email  | Nenhuma    | 2 req/s (IP)    |
| GET    | /v1/users               | ROLE_USER  | 10 req/s (user) |
| GET    | /v1/users/{id}          | ROLE_USER  | 10 req/s (user) |
| GET    | /v1/users/email/{email} | ROLE_USER  | 10 req/s (user) |
| GET    | /v1/users/me            | ROLE_USER  | 10 req/s (user) |
| PUT    | /v1/users               | ROLE_USER  | 10 req/s (user) |
| POST   | /v1/users/resend-verification | ROLE_USER | 10 req/s (user) |
| DELETE | /v1/users/remove/me     | ROLE_USER  | 10 req/s (user) |
| DELETE | /v1/users/delete/me     | ROLE_USER  | 10 req/s (user) |

**Semânticas de DELETE (intencionais, ADR-001/ADR-013):**

- `DELETE /v1/users/remove/me` (USER) → soft-delete (`deactivateUser`, `active=false`)
- `DELETE /v1/users/delete/me` (USER) → hard-delete (`deleteUser`)

> As rotas administrativas `DELETE /v1/users/{id}` e `DELETE /v1/users/del/{id}` foram removidas
> deste controller (ADR-013) e absorvidas pelo `AdminController` dedicado — ver
> [Endpoints administrativos](#endpoints-administrativos-v1admin) abaixo (ADR-014).

### Endpoints administrativos (`/v1/admin/**`)

Todos exigem `ROLE_ADMIN` via `@PreAuthorize` no user-service (o gateway não enforce `hasRole()`
— autorização por role é sempre downstream, ver ADR-014). Rate limit MED (5 req/s, cap 10/IP) na
rota `admin-service` do gateway — superfície sensível, poucos operadores esperados.

| Método | Path                              | Auth       | Rate Limit | Descrição |
| ------ | ---------------------------------- | ---------- | ---------- | --------- |
| GET    | /v1/admin/users                    | ROLE_ADMIN | MED (5/s)  | Listagem completa incl. inativos, filtros opcionais `active`/`name`/`email`, paginada |
| GET    | /v1/admin/users/{id}/audit-logs    | ROLE_ADMIN | MED (5/s)  | Trilha de auditoria LGPD de um titular específico, paginada |
| GET    | /v1/admin/audit-logs               | ROLE_ADMIN | MED (5/s)  | Feed geral da trilha de auditoria LGPD, paginado |
| PATCH  | /v1/admin/users/{id}/roles         | ROLE_ADMIN | MED (5/s)  | Promove/revoga roles (`USER`/`ADMIN`) de um titular |
| POST   | /v1/admin/users/{id}/resend-verification | ROLE_ADMIN | MED (5/s) | Reenvia o e-mail de verificação de cadastro para um titular |
| DELETE | /v1/admin/users/{id}                | ROLE_ADMIN | MED (5/s)  | Soft-delete administrativo de outro titular (absorvido do ADR-013) |
| DELETE | /v1/admin/users/del/{id}            | ROLE_ADMIN | MED (5/s)  | Hard-delete administrativo de outro titular (absorvido do ADR-013) |

**Paginação de auditoria com teto:** `GET .../audit-logs` (ambos) aplicam um clamp de tamanho de
página (`AdminService.MAX_AUDIT_PAGE_SIZE = 100`) — um `size` maior é truncado, não satisfeito
integralmente, para impedir que um token ADMIN comprometido drene toda a trilha LGPD numa única
requisição.

**Regras de negócio do `PATCH /v1/admin/users/{id}/roles`:**

- `roles` deve ser ⊆ `{USER, ADMIN}` — valor fora do conjunto → **400**.
- `roles` deve conter `USER` — payload que o omita é rejeitado com **400** (sem normalização
  silenciosa).
- **Auto-revogação bloqueada:** se o ator (`userID` do JWT) == `{id}` do path e o estado
  persistido no MongoDB (não o JWT, que pode estar stale) contém `ADMIN` e o payload remove
  `ADMIN` → **409 Conflict** (evita lockout operacional).
- Em sucesso: evicta `usersById`/`usersByEmail`/`authByEmail` e audita `ROLE_GRANT` ou
  `ROLE_REVOKE` (nunca os dois; nenhum se não houve mudança efetiva em `ADMIN`).

Detalhe completo em [ADR-014](adr/ADR-014-admin-controller-gestao-roles-auditoria.md).

### Verificação de e-mail no cadastro (ADR-015)

Cadastro (`POST /v1/users/register`) só seta `emailVerified=false` — **não dispara** mais
o e-mail de confirmação automaticamente. O envio (outbox `notificationOutbox` →
notification-service via Feign) só acontece quando explicitamente requisitado via um dos
endpoints de reenvio abaixo.

**`GET /v1/users/verify-email?token=...`** — público, pré-sessão, tier LOW (2/s) por IP no gateway
(mesmo de `/v1/users/register`):

- Token opaco (256 bits, `SecureRandom` + Base64URL) — **não é JWT**; só o hash SHA-256
  é persistido no outbox. TTL de 15 min (`app.verification.token-ttl`).
- Resposta genérica em caso de token inexistente/expirado/já usado (não revela o motivo
  específico — evita oráculo de enumeração). Idempotente: clique duplicado num link já
  confirmado é sucesso silencioso.
- Sucesso audita `EMAIL_VERIFIED` (`AuditAction`, ator USER) na trilha LGPD.

**Reenvio do e-mail de verificação** — deixou de ser público/por-e-mail; agora existem duas
rotas autenticadas, ambas delegando a `EmailVerificationService.resendByUserId(String)`:

| Método | Path                                       | Auth       | Rate Limit | Descrição |
| ------ | -------------------------------------------- | ---------- | ---------- | --------- |
| POST   | /v1/users/resend-verification                | ROLE_USER  | 10/s (user) | Self-service: reenvia para o próprio usuário (`userID` do JWT) |
| POST   | /v1/admin/users/{id}/resend-verification     | ROLE_ADMIN | MED (5/s)  | Admin: reenvia para qualquer titular, por `{id}` |

- Sem corpo — o titular é resolvido por `userID` do JWT (self) ou `{id}` do path (admin), nunca
  por e-mail no body (evita o vetor de anti-enumeração por e-mail que existia antes).
- `resendByUserId` lança `DomainEntityNotFound` (404) se o `{id}` não existir — diferente do
  antigo `resend(email)` (ainda usado internamente, silencioso por design): aqui não há motivo de
  anti-enumeração, pois o chamador já está autenticado (self) ou é admin (que opera por ID de uma
  listagem existente).
- Resposta de sucesso é `202 Accepted`; reenvio **não é auditado** em `auditLogs` (mesmo critério
  de antes — evita ruído/oráculo de timing).
- Rate limit **complementar por conta-alvo** (`ResendRateLimitService`, Redis, chave
  `sha256(emailLower)`, janela fixa de 1h, default 3/h — `app.verification.resend-max-per-window`/
  `app.verification.resend-window`), além do tier por-usuário do gateway — evita e-mail bombing.
- Supera (`SUPERSEDED`) qualquer outbox `PENDING`/`SENT` anterior do mesmo titular antes de
  emitir um novo token — só o mais recente é válido.

**Gate de login + grace period (authorization-server):** `AuthorizationService.loadUserByUsername`
mapeia `AuthDTO.emailVerified` para `enabled` — `emailVerified=false` bloqueia o login
(`DisabledException`, mensagem genérica). Mitigado por janela de carência de 24h
(`security.email-verification.grace-period`) desde `AuthDTO.registrationDate` (campo aditivo,
nullable no lado consumidor — `null` equivale a usuário legado, sem NPE). Usuários legados
(`emailVerified=null`) continuam tratados como verificados, sem bloqueio.

**Canal interno (notification-service):** `POST /internal/notifications/email-verification`
— protegido por `X-Internal-Token` (mesmo shared secret do ADR-006), sem Spring Security
(`Filter` de servlet simples). Chamado só pelo user-service via Feign assíncrono
(`@Async`, executor `notificationExecutor`) com circuit breaker Resilience4j
(`configs.notification-service`) + `NotificationClientFallbackFactory`. **Nunca exposto pelo
gateway.** Payload:

```json
{
  "email": "maria@exemplo.com",
  "name": "Maria Silva",
  "verificationLink": "http://localhost:8081/v1/users/verify-email?token=<token-opaco>"
}
```

Detalhe completo (decisões e alternativas consideradas) em
[ADR-015](adr/ADR-015-verificacao-email-cadastro.md).

**Leituras só de usuários ativos (ADR-001):** os endpoints de leitura ocultam usuários
soft-deleted (`active=false`):

- `GET /v1/users` lista apenas ativos (`findByActiveTrue`).
- `GET /v1/users/{id}` e `GET /v1/users/email/{email}` retornam **404** para usuário inativo
  (tratado como inexistente).
- `GET /v1/users/me` retorna **404** se a própria conta estiver desativada.

> Ver _Convenções_ no [CLAUDE.md](../CLAUDE.md) e [ADR-001](adr/ADR-001-leitura-somente-ativos.md).

## Exemplos de payload

**Request — `POST /v1/users/register`** (Bean Validation; `name` 1–50 chars, `email` formato e-mail, `password` obrigatória, 8–72 chars com ao menos uma letra e um número; `termsAccepted` **obrigatório e `true`** — consentimento LGPD, ADR-012 — só no cadastro. No `PUT /v1/users` a senha é opcional (omitida/null mantém a atual) e `termsAccepted` é ignorado):

```json
{
  "name": "Maria Silva",
  "email": "maria@exemplo.com",
  "password": "senhaSegura123",
  "termsAccepted": true
}
```

**Response — `UserResponseDTO`** (não expõe `passwordHash` nem `roles`; logo após o cadastro
`emailVerified` vem `false` e `emailVerifiedAt` vem `null` — só vira `true`/preenchido após
`GET /v1/users/verify-email`, ADR-015):

```json
{
  "id": "665f1c2e8a3b4c0012abcd34",
  "name": "Maria Silva",
  "email": "maria@exemplo.com",
  "registrationDate": "2026-06-08T14:32:10.123",
  "active": true,
  "consentAcceptedAt": "2026-06-08T14:32:10.123",
  "termsVersion": "v1",
  "tenantIds": null,
  "emailVerified": false,
  "emailVerifiedAt": null
}
```

`tenantIds` é reservado para uma feature futura de multi-tenant (sempre `null` hoje —
`registerUser` não atribui tenant). `emailVerified`/`emailVerifiedAt` deixaram de ser scaffold
inerte (ADR-015): `registerUser` seta `emailVerified=false` no cadastro, mas **não** dispara
o envio do e-mail — o fluxo de verificação (`GET /v1/users/verify-email`) só começa quando o
usuário (self) ou um admin chama explicitamente um dos endpoints de reenvio; `emailVerifiedAt`
é preenchido na confirmação. `null` (registros legados, anteriores ao campo) continua tratado
como verificado em toda leitura.

## Claims do JWT

O `TokenCustomizerConfig.java` (authorization-server) injeta os seguintes claims no access token. Desde C14, `userID` e `roles` são lidos das authorities do `Authentication` (carregadas por `AuthorizationService.loadUserByUsername`) — sem chamada Feign adicional na fase de emissão do token:

| Claim         | Origem                       | Exemplo                          |
| ------------- | ---------------------------- | -------------------------------- |
| `userID`      | ID do usuário no MongoDB     | `"665f1c2e8a3b4c0012abcd34"`     |
| `roles`       | `user.getRoles()`            | `["USER"]`                       |
| `permissions` | derivado das roles           | `["users.read","users.write"]`  |
| `scope`       | `context.getAuthorizedScopes()` | `["openid","profile"]`        |

```json
{
  "sub": "maria@exemplo.com",
  "userID": "665f1c2e8a3b4c0012abcd34",
  "roles": ["USER"],
  "permissions": ["users.read", "users.write"],
  "scope": ["openid", "profile"]
}
```

## Schema MongoDB (coleção `users`)

```js
{
  _id: ObjectId,
  name: String,          // 1–50 chars
  email: String,         // unique, formato e-mail
  passwordHash: String,  // BCrypt (custo 10)
  registrationDate: ISODate,
  roles: [String],       // ex: ["USER"], ["USER", "ADMIN"]
  active: Boolean,
  consentAcceptedAt: ISODate, // consentimento LGPD no cadastro (ADR-012); nullable p/ legados
  termsVersion: String,       // versão dos termos aceita (ex: "v1"); nullable p/ legados
  tenantIds: [String],        // reservado p/ multi-tenant futuro; sempre null hoje (sem atribuição)
  emailVerified: Boolean,     // ADR-015: registerUser seta false no cadastro; true após confirmação
                               //   (GET /v1/users/verify-email); null (legado) tratado como true
  emailVerifiedAt: ISODate    // preenchido na confirmação do e-mail; nullable p/ não confirmados/legados
}
```

## Schema MongoDB (coleção `notificationOutbox`)

Outbox sem poller (ADR-015) — criado e processado no mesmo evento que o originou (cadastro ou
reenvio), sem `@Scheduled`/scan periódico. Índice composto `(userId, type, status)`.

```js
{
  _id: ObjectId,
  userId: String,           // indexado
  type: String,              // EMAIL_VERIFICATION (único valor hoje)
  tokenHash: String,         // SHA-256 do token opaco (unique); o token em claro nunca é persistido
  expiresAt: ISODate,        // TTL do token (15 min default, app.verification.token-ttl)
  status: String,            // PENDING | SENT | FAILED | CONFIRMED | SUPERSEDED
  createdAt: ISODate,
  lastAttemptAt: ISODate,
  attempts: Number,
  purgeAt: ISODate           // TTL index do Mongo (expireAfterSeconds: 0) — retenção separada
                               //   de expiresAt (createdAt + 30 dias default, preserva histórico
                               //   de auditoria do outbox; app.verification.outbox-retention)
}
```

> O TTL index do Mongo atua sobre `purgeAt`, **não** sobre `expiresAt` — aplicar o TTL direto em
> `expiresAt` apagaria registros `CONFIRMED`/`SENT` pouco depois da expiração do token, perdendo
> o histórico do outbox. Detalhe completo em [ADR-015](adr/ADR-015-verificacao-email-cadastro.md).

## Schema MongoDB (coleção `auditLogs`)

Trilha de auditoria de acesso a dado pessoal (LGPD, ADR-011) — distinta do log operacional SLF4J.
Append-only; escrita assíncrona pelo `AuditService`. Índice composto `(targetUserId, timestamp desc)`.

```js
{
  _id: ObjectId,
  timestamp: ISODate,        // quando a operação ocorreu
  action: String,            // REGISTER | UPDATE | SOFT_DELETE_ADMIN | HARD_DELETE_ADMIN
                             //   | SOFT_DELETE_SELF | HARD_DELETE_SELF | READ_INTERNAL_CREDENTIAL
                             //   | READ_CROSS_SUBJECT | ROLE_GRANT | ROLE_REVOKE
  actorType: String,         // USER | ADMIN | SYSTEM
  actorUserId: String,       // null quando SYSTEM
  actorRoles: [String],      // null quando SYSTEM
  targetUserId: String,      // titular do dado (quando aplicável)
  targetEmail: String,       // mascarado (ex: "f***@email.com"); null quando não aplicável
  correlationId: String      // traceId B3, para correlação com logs/Zipkin
}
```

> Leitura do próprio dado (`/me`, consulta ao próprio ID/email) **não** gera trilha. A listagem
> (`GET /v1/users`) não é auditada (escopo inicial). Sem TTL — ver ADR-011. A consulta da trilha
> via API existe desde o ADR-014: `GET /v1/admin/audit-logs` (feed geral) e
> `GET /v1/admin/users/{id}/audit-logs` (por titular), ambos ADMIN-only e paginados.

## Estratégia de cache (Redis)

TTL de 5 min, três caches distintos:

| Cache          | Chave   | Valor             | Onde popula                                            |
| -------------- | ------- | ----------------- | ------------------------------------------------------ |
| `usersById`    | ID      | `UserResponseDTO` | `@Cacheable` em `searchById` (`key = "#userID"`)       |
| `usersByEmail` | e-mail  | `UserResponseDTO` | `@Cacheable` em `searchByEmail` (`key = "#email"`)     |
| `authByEmail`  | e-mail  | `AuthDTO`         | `@Cacheable` em `AuthenticationService.getUserByEmail` |

**Leitura (declarativa):** via `@Cacheable` nos métodos acima.

**Escrita (manual via `CacheService`, que encapsula o `CacheManager`):**

- `updateUser` — atualiza `usersById` e `usersByEmail` (novo e-mail) e **evicta** o e-mail antigo em `usersByEmail` e `authByEmail`.
- `deleteUser` e `deactivateUser` — **evictam** os três caches.

> A escrita é manual (não declarativa) porque cada cache usa uma chave diferente — ID vs e-mail.

## Formato de erros (RFC 7807 / ProblemDetail)

Todos os erros retornam `Content-Type: application/problem+json` com o schema abaixo:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "User was not found for parameters {ID=abc}"
}
```

| Status | `title`                 | Situação                                                        |
| ------ | ----------------------- | --------------------------------------------------------------- |
| 400    | `Bad Request`           | Argumento inválido (`IllegalArgumentException`)                 |
| 400    | `Validation Failed`     | Bean Validation (`@Valid`) — inclui propriedade extra `errors`  |
| 404    | `Not Found`             | Entidade não encontrada (`DomainEntityNotFound`)                |
| 409    | `Conflict`              | E-mail já cadastrado — `detail` fixo: `"Email already registered"` |
| 500    | `Internal Server Error` | Erro não tratado — `detail` fixo: `"Erro interno"`             |

**Resposta 400 de validação — propriedade extra `errors`:**

```json
{
  "type": "about:blank",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Validation failed",
  "errors": [
    { "field": "email", "message": "must be a well-formed email address" }
  ]
}
```
