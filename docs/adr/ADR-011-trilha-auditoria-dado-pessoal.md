# ADR-011: Trilha de auditoria de acesso a dado pessoal (LGPD)

- **Status:** aceita
- **Data:** 2026-06-17
- **Serviço alvo:** user-service
- **Tarefa relacionada:** RELATORIOA item 1.4 (audit log de acesso a dado pessoal)

## Contexto

Como controlador de dados pessoais (LGPD), o sistema precisa registrar *quem acessou/alterou/apagou
qual dado de qual titular, quando*. Havia apenas log operacional SLF4J (efêmero, com PII mascarada),
que não é uma trilha de auditoria: não é consultável por titular nem durável como registro de negócio.

Restrições do projeto: o dado pessoal vive no **user-service** (Mongo, coleção `users`); o
auth-server não acessa o Mongo (só via Feign no canal interno). A identidade do solicitante chega
como claims do JWT (`userID`, `roles`) nos endpoints do gateway, e como ator **SYSTEM** no canal
interno (`/internal/users/email/{email}`, protegido por `X-Internal-Token`).

## Decisão

Nova coleção MongoDB **`auditLogs`** no user-service, alimentada por um `AuditService`.

**Modelo (`AuditLog`):** `timestamp`, `action` (`AuditAction`), `actorType` (USER/ADMIN/SYSTEM),
`actorUserId`, `actorRoles`, `targetUserId`, `targetEmail` (**mascarado** via `LogUtils.maskEmail`),
`correlationId` (traceId B3). Índice composto `(targetUserId, timestamp desc)` para a consulta natural
"histórico de acesso de um titular".

**Escopo de captura** (mutações + leituras sensíveis), no nível dos controllers (onde ator + alvo +
semântica da ação são inequívocos):

| Ação | Origem | Ator |
|---|---|---|
| `REGISTER` | `POST /v1/users/register` | self (novo usuário) |
| `UPDATE` | `PUT /v1/users` | self (JWT) |
| `SOFT_DELETE_ADMIN` | `DELETE /v1/admin/users/{id}` (ver ADR-014) | ADMIN (JWT) |
| `HARD_DELETE_ADMIN` | `DELETE /v1/admin/users/del/{id}` (ver ADR-014) | ADMIN (JWT) |
| `SOFT_DELETE_SELF` | `DELETE /v1/users/remove/me` | self (JWT) |
| `HARD_DELETE_SELF` | `DELETE /v1/users/delete/me` | self (JWT) |
| `READ_INTERNAL_CREDENTIAL` | `GET /internal/users/email/{email}` | SYSTEM |
| `READ_CROSS_SUBJECT` | `GET /v1/users/{id}` e `/email/{email}` quando o titular ≠ solicitante | USER/ADMIN (JWT) |
| `ROLE_GRANT` / `ROLE_REVOKE` | `PATCH /v1/admin/users/{id}/roles` (ver ADR-014) | ADMIN (JWT) |

A leitura do **próprio** dado (`/me`, ou consulta ao próprio ID/email) **não** é auditada — baixo
valor, alto volume. A auditoria é gravada **após o sucesso** da operação (operações que falham em
404/403 não acessaram/alteraram dado).

**Escrita assíncrona e isolada de falha** (`AuditAsyncConfig`): executor dedicado `auditExecutor`
(`@Async`), com `TaskDecorator` copiando o MDC (traceId) para a thread do executor; falha ao persistir
é logada em ERROR e **nunca** propaga para o caminho de negócio. Rejeição `CallerRunsPolicy`: sob
saturação degrada para síncrona em vez de descartar entradas.

**`correlationId` em vez de `sourceIp`:** no user-service o IP de origem seria o do gateway (hop
interno), não o do cliente — enganoso. O `correlationId` (traceId B3) liga a entrada de auditoria ao
trace completo, inclusive ao log de borda do gateway, que é onde o IP real do cliente é registrado
(ADR-010).

## Consequências

- **Positivo:** trilha durável e consultável por titular; cumpre a exigência LGPD de "trilha de
  auditoria de acesso". Distinta do log operacional. Sem latência no caminho de negócio (async).
- **Negativo / dívida aceita:**
  - **Async = risco de perda** de entradas em crash antes do flush do executor (aceito para o nível
    atual; durabilidade síncrona seria a evolução se a exigência subir).
  - **Listagem (`GET /v1/users`) não é auditada** — acesso em massa a múltiplos titulares ficou fora
    do escopo inicial (evita ruído de polling do SPA); registrar como limitação conhecida.
  - **Sem endpoint de consulta** — a trilha se lia via acesso direto ao Mongo por ora (decisão de
    escopo original). Endereçado por [ADR-014](ADR-014-admin-controller-gestao-roles-auditoria.md):
    `GET /v1/admin/audit-logs` e `GET /v1/admin/users/{id}/audit-logs`, ADMIN-only, paginados.
  - **Sem retenção/TTL** definido — a coleção cresce indefinidamente (política de retenção é trabalho
    futuro, sensível à própria LGPD: minimização vs. prova de auditoria).
- **Consumidores afetados:** `UserController` e `InternalUserController` ganham dependência de
  `AuditService` e (nos reads/admin-deletes) o `@AuthenticationPrincipal Jwt`. Nenhuma mudança de
  contrato externo (mesmos endpoints/respostas).
- **Testes:** `AuditServiceTest` (unit, incl. isolamento de falha), `AuditLogIntegrationTest`
  (persistência real no Mongo, escrita async via Awaitility), e verificações de auditoria em
  `UserControllerTest`/`InternalUserControllerTest` (incl. que `/me` e leitura do próprio dado não
  geram trilha).

## Alternativas consideradas

- **Auditar na camada de serviço:** rejeitado — `deactivateUser` é compartilhado por admin e self;
  só o controller distingue ator/ação (e tem o JWT).
- **Síncrono no caminho de request:** durável, mas adiciona latência e acopla o sucesso da operação à
  escrita de auditoria. Async + isolamento de falha foi preferido para o nível atual.
- **Auditar todas as leituras (inclusive `/me`):** alto volume/custo sem valor proporcional —
  excluído o self-read.
- **Sink externo (arquivo append-only / SIEM):** evolução possível; o Mongo reaproveita a infra
  existente (`MongoConfig`, auto-index) e entrega a trilha já.
