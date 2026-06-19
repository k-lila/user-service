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
| GET    | /v1/users               | ROLE_USER  | 10 req/s (user) |
| GET    | /v1/users/{id}          | ROLE_USER  | 10 req/s (user) |
| GET    | /v1/users/email/{email} | ROLE_USER  | 10 req/s (user) |
| GET    | /v1/users/me            | ROLE_USER  | 10 req/s (user) |
| PUT    | /v1/users               | ROLE_USER  | 10 req/s (user) |
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

**Response — `UserResponseDTO`** (não expõe `passwordHash` nem `roles`):

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
  "emailVerified": true,
  "emailVerifiedAt": "2026-06-08T14:32:10.123"
}
```

`tenantIds` é reservado para uma feature futura de multi-tenant (sempre `null` hoje —
`registerUser` não atribui tenant). `emailVerified`/`emailVerifiedAt` também são scaffold:
sem fluxo de verificação de e-mail implementado, `registerUser` sempre seta
`emailVerified=true`; `null` (registros legados) é tratado como verificado em toda leitura.

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
  emailVerified: Boolean,     // scaffold sem fluxo de verificação; registerUser sempre seta true;
                               //   null (legado) tratado como true em toda leitura
  emailVerifiedAt: ISODate    // nullable; sem fluxo de verificação implementado
}
```

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
