# Referência da API (user-service)

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Endpoints expostos via gateway](#endpoints-expostos-via-gateway)
- [Exemplos de payload](#exemplos-de-payload)
- [Claims do JWT](#claims-do-jwt)
- [Schema MongoDB (coleção `users`)](#schema-mongodb-coleção-users)
- [Estratégia de cache (Redis)](#estratégia-de-cache-redis)

## Endpoints expostos via gateway

| Método | Path                 | Auth       | Rate Limit      |
| ------ | -------------------- | ---------- | --------------- |
| POST   | /users/register      | Nenhuma    | 2 req/s (IP)    |
| GET    | /users               | ROLE_USER  | 10 req/s (user) |
| GET    | /users/{id}          | ROLE_USER  | 10 req/s (user) |
| GET    | /users/email/{email} | ROLE_USER  | 10 req/s (user) |
| GET    | /users/me            | ROLE_USER  | 10 req/s (user) |
| PUT    | /users               | ROLE_USER  | 10 req/s (user) |
| DELETE | /users/{id}          | ROLE_ADMIN | 10 req/s (user) |
| DELETE | /users/del/{id}      | ROLE_ADMIN | 10 req/s (user) |
| DELETE | /users/remove/me     | ROLE_USER  | 10 req/s (user) |

**Semânticas de DELETE (intencionais):**

- `DELETE /users/{id}` (ADMIN) → soft-delete (`deactivateUser`, `active=false`)
- `DELETE /users/del/{id}` (ADMIN) → hard-delete (`deleteUser`)
- `DELETE /users/remove/me` (USER) → soft-delete

> Ver _Convenções_ no [CLAUDE.md](../CLAUDE.md).

## Exemplos de payload

**Request — `POST /users/register`** (validado por `@Valid`; `name` 1–50 chars, `email` formato e-mail, `password` mín. 8 chars):

```json
{
  "name": "Maria Silva",
  "email": "maria@exemplo.com",
  "password": "senhaSegura123"
}
```

**Response — `UserResponseDTO`** (não expõe `passwordHash` nem `roles`):

```json
{
  "id": "665f1c2e8a3b4c0012abcd34",
  "name": "Maria Silva",
  "email": "maria@exemplo.com",
  "registrationDate": "2026-06-08T14:32:10.123",
  "active": true
}
```

## Claims do JWT

O `TokenCustomizerConfig.java` (authorization-server) injeta os seguintes claims no access token:

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
  active: Boolean
}
```

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
