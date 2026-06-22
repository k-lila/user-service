# ADR-016: Leitura de PII por id/e-mail restrita a ADMIN (fix do G1/IDOR)

- **Status:** aceita
- **Data:** 2026-06-21
- **Serviço alvo:** user-service
- **Tarefa relacionada:** G1 (auditoria de segurança de 2026-06-21, `docs/SECURITY.md`) — IDOR de leitura de PII

## Contexto

A auditoria de segurança de 2026-06-21 (`security-reviewer`) identificou o **G1 (severidade
ALTO)**: as rotas públicas de leitura por identificador exigiam apenas `hasRole('USER')`, sem
checar titularidade:

- `GET /v1/users/{id}` (`UserController.searchById`)
- `GET /v1/users/email/{email}` (`UserController.searchByEmail`)

Qualquer usuário autenticado podia iterar ids/e-mails e ler PII (nome, e-mail, `registrationDate`,
`consentAcceptedAt`, `emailVerified`) de **toda a base** — enumeração/exfiltração, insumo de
phishing e **violação direta da LGPD** (acesso a dado pessoal de terceiro). O código já **auditava**
a leitura cross-subject (`READ_CROSS_SUBJECT`), mas **não a bloqueava**.

Restrições do projeto observadas: roles fixas `USER`/`ADMIN`; autorização por role é sempre
*downstream* via `@PreAuthorize` (o gateway não ganha `hasRole()`); separação rígida do canal
interno (`InternalUserController` → auth-server via Feign, intocado); trilha de auditoria LGPD
(ADR-011) deve continuar registrando o acesso. Esta decisão dá continuidade ao ADR-013 (remoção de
rotas administrativas do `UserController`) e ao ADR-014 (`AdminController` dedicado).

## Decisão

1. **Remover do `UserController`** os handlers `GET /v1/users/{id}` e `GET /v1/users/email/{email}`
   (e o helper privado `auditCrossSubjectRead`). A leitura do próprio dado permanece via
   `GET /v1/users/me` (`hasRole('USER')`), inalterada.
2. **Adicionar ao `AdminController`** (`@PreAuthorize("hasRole('ADMIN')")`, ADR-014):
   - `GET /v1/admin/users/{id}` → `AdminService.findById`
   - `GET /v1/admin/users/email/{email}` → `AdminService.findByEmail`

   Ambos retornam **`AdminUserResponseDTO`** (inclui `roles`, consistente com a superfície admin) e
   **incluem titulares inativos** (soft-deleted) — coerente com `listAllUsers` (ADR-014); 404
   (`DomainEntityNotFound`) só quando o titular não existe. Sem cache (leitura admin é baixo volume;
   evita colisão de tipo com os caches `usersById`/`usersByEmail`, que guardam `UserResponseDTO`).
3. **Auditoria:** novo valor `AuditAction.ADMIN_READ_USER` — toda leitura admin por id/e-mail é
   auditada (rastro LGPD de *qual admin acessou o dado de qual titular*). O valor `READ_CROSS_SUBJECT`
   **permanece** no enum, marcado `@Deprecated` (não mais emitido), para desserializar registros
   históricos de `auditLogs`.
4. **Gateway:** sem alteração. As novas rotas casam na rota genérica `admin-service` (`/v1/admin/**`,
   `tokenRelay` + rate-limit MED por usuário). `ROLE_ADMIN` é checado só *downstream*.
5. **Camada de serviço:** `SearchService.searchByEmail` é **mantido** (suporte ao cache
   `usersByEmail`, populado/evictado por `RegisterService`/`EmailVerificationService`); deixa de ser
   exposto por rota HTTP. A leitura por e-mail exposta passa a ser exclusivamente
   `AdminService.findByEmail` (sem cache).
6. **Schema (MongoDB):** sem mudança. A resposta exposta muda de `UserResponseDTO` (sem `roles`) para
   `AdminUserResponseDTO` (com `roles`) — mas só na nova superfície ADMIN-only, sem ampliar exposição
   indevida.

## Consequências

- **Positivo:** fecha o G1 — PII de terceiro deixa de ser legível por qualquer `USER`. A enumeração
  por id/e-mail passa a exigir `ROLE_ADMIN`. `UserController` fica coeso (só dado do próprio
  titular). Acesso admin a PII fica auditado (`ADMIN_READ_USER`), reforçando o dever LGPD de
  rastrear acesso a dado pessoal.
- **Contrato (mudança):** `GET /v1/users/{id}` e `GET /v1/users/email/{email}` deixam de existir —
  um `USER` que as chamava passa a receber **403** (rota admin) ou **404** (rota pública inexistente).
  O front-end (`login-interface`) consome apenas `/v1/users/me` e `/v1/users/register` — **não
  afetado**. Nenhum serviço interno (gateway, auth-server) dependia dessas rotas (o auth-server usa
  o canal interno `/internal/users/email/{email}`, intocado).
- **Negativo / dívida residual:** `SearchService.searchByEmail` fica sem consumidor HTTP (mantido
  apenas como suporte ao cache `usersByEmail`); o cache passa a ter populador (`putByEmail` em
  `RegisterService.updateUser`) mas leitura só via esse método de serviço. Aceito para não ampliar o
  blast radius da correção a um refactor de cache.
- **Observabilidade:** nova ação `ADMIN_READ_USER` na trilha `auditLogs`; consultável via
  `GET /v1/admin/audit-logs` (ADR-014).
- **Testes:** `UserControllerTest` perde os casos de `/{id}` e `/email/{email}` (incl. auditoria
  `READ_CROSS_SUBJECT`); os casos de serialização de `emailVerified`/`tenantIds` migram para `/me`.
  `AdminControllerTest` ganha 200/403/401/404 + auditoria `ADMIN_READ_USER` para as duas rotas.
  `AdminServiceTest` e `AdminFlowIntegrationTest` cobrem `findById`/`findByEmail` (ativo, inativo,
  inexistente).

## Alternativas consideradas

- **Restringir ao próprio titular (self) em vez de ADMIN:** descartado — a decisão de produto é que
  a leitura por id/e-mail de qualquer titular é operação administrativa; o próprio titular já se lê
  via `/me`. Restringir a self tornaria as rotas redundantes com `/me`.
- **Manter as rotas no `UserController` e apenas adicionar checagem de titularidade:** descartado —
  perpetuaria a mistura de escopos (self vs. admin) no controller público; a superfície admin
  dedicada (ADR-014) é o lugar natural para leitura administrativa, com `AdminUserResponseDTO` e
  auditoria próprias.
- **Reduzir o `UserResponseDTO` público (remover campos sensíveis) mantendo `hasRole('USER')`:**
  descartado — não fecha a enumeração (nome/e-mail já são PII) nem o acesso cross-subject; trata o
  sintoma, não a causa.
- **Cachear `AdminService.findById`/`findByEmail` sob `usersById`/`usersByEmail`:** descartado —
  colisão de tipo (esses caches guardam `UserResponseDTO`, não `AdminUserResponseDTO`); leitura
  admin é baixo volume, sem ganho que justifique o risco.
