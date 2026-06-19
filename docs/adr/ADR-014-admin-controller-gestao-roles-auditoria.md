# ADR-014: AdminController dedicado — listagem administrativa, consulta de auditoria LGPD e gestão de roles

- **Status:** aceita
- **Data:** 2026-06-18
- **Serviço alvo:** user-service (+ rota nova no gateway)
- **Tarefa relacionada:** evolução do domínio — superfície administrativa dedicada (spec
  `product-manager`, aprovada em 3 rodadas de `senso-critico`)

## Contexto

Três gaps documentados motivaram esta tarefa:

- **ADR-001** previa que uma visão administrativa incluindo usuários inativos seria "um novo
  endpoint admin-only, registrado em ADR próprio" — esta é essa ADR.
- **ADR-011** registrou a trilha de auditoria LGPD (`auditLogs`) como gravada, mas sem nenhum
  endpoint de consulta — dívida explícita.
- **ADR-013** removeu do `UserController` os 2 endpoints administrativos de delete
  (`DELETE /v1/users/{id}` soft-delete, `DELETE /v1/users/del/{id}` hard-delete), reservando
  `SOFT_DELETE_ADMIN`/`HARD_DELETE_ADMIN` no enum `AuditAction` para um "`AdminUserController`
  dedicado, trabalho futuro". Esta ADR fecha essa dívida: o controller introduzido aqui —
  nomeado `AdminController` (não `AdminUserController`, ajuste de nome sem efeito de
  contrato) — é esse controller futuro.

Não existia, além disso, nenhum mecanismo de promoção/revogação de `ADMIN`: roles eram só
atribuídas (`USER`) no registro. Restrições do projeto observadas: roles fixas `USER`/`ADMIN`
(strings simples, sem roles dinâmicas); BCrypt custo 10 inalterado; separação rígida
auth-server/user-service preservada (nenhuma mudança no canal Feign `/internal/...`); cache
Redis (`usersById`/`usersByEmail`/`authByEmail`) deve permanecer coerente após qualquer
mutação; trilha de auditoria LGPD (ADR-011) deve continuar registrando toda mutação relevante.

## Decisão

### 1. Novo `AdminController` (`/v1/admin`), todo método `@PreAuthorize("hasRole('ADMIN')")`

| Método | Path | Descrição |
|---|---|---|
| GET | `/v1/admin/users` | Listagem completa (inclui inativos), filtros opcionais `active`/`name`/`email`, paginada |
| GET | `/v1/admin/users/{id}/audit-logs` | Trilha de auditoria de um titular específico |
| GET | `/v1/admin/audit-logs` | Feed geral de auditoria, paginado |
| PATCH | `/v1/admin/users/{id}/roles` | Promove/revoga roles (`USER`/`ADMIN`) de um titular |
| DELETE | `/v1/admin/users/{id}` | Soft-delete administrativo de outro titular (absorvido do ADR-013) |
| DELETE | `/v1/admin/users/del/{id}` | Hard-delete administrativo de outro titular (absorvido do ADR-013) |

Enforcement de `ROLE_ADMIN` é **exclusivamente** via `@PreAuthorize`, no user-service — o
gateway não ganha `hasRole()` nesta tarefa (decisão explícita, consistente com o padrão já
usado nos endpoints self-service: autorização por role é sempre downstream).

### 2. Schema novo — `AuditAction.ROLE_GRANT` / `ROLE_REVOKE`

Mudança aditiva, não-destrutiva, no enum já existente (ADR-011). `SOFT_DELETE_ADMIN` e
`HARD_DELETE_ADMIN` (reservados pelo ADR-013) passam a ter rota ativa — sem novo valor de
enum para os deletes administrativos.

### 3. DTOs novos, isolados do contrato existente

- `AdminUserResponseDTO` — campos de `UserResponseDTO` + `roles: Set<String>`. Usado **só**
  por `AdminController` (listagem e resposta do PATCH). `UserResponseDTO` (usado por
  `UserController`) permanece **inalterado**, sem `roles` — expor roles aqui não amplia
  exposição indevida (superfície é ADMIN-only) e é necessário: sem isso a própria gestão de
  roles ficaria às ciegas.
- `AuditLogResponseDTO` — não expõe a entidade `AuditLog` crua.
- `UpdateRolesRequestDTO { Set<String> roles }` — payload do PATCH.

### 4. Regras de negócio de `PATCH /v1/admin/users/{id}/roles`

1. `newRoles` deve ser ⊆ `{USER, ADMIN}` — qualquer outro valor → `IllegalArgumentException`
   → **400**.
2. `newRoles` deve **conter** `USER` — payload que o omita é **rejeitado com 400** (decisão
   de produto confirmada: não normaliza silenciosamente adicionando `USER`). Comportamento
   explícito e auditável, sem efeito colateral surpresa na trilha de auditoria.
3. **Bloqueio de auto-revogação:** se o claim `userID` do JWT do ator == `{id}` do path **e**
   o estado de roles **persistido no MongoDB** (lido antes do save, nunca o JWT — que pode
   estar stale por até o TTL do cache `authByEmail`) contém `ADMIN` **e** o payload remove
   `ADMIN` → `SelfRoleRevocationException` → **409 Conflict** (distinto de 400: é conflito
   com uma invariante de estado/operação, não erro de formato). Evita lockout operacional —
   nenhuma rota de recuperação via API existiria se o quadro de admins fosse zerado.
4. Em sucesso: persiste, evicta os 3 caches (`usersById`, `usersByEmail`, `authByEmail` via
   `CacheService`, já reaproveitado sem alteração) e retorna `AdminUserResponseDTO`.
5. `AdminService.updateUserRoles` retorna um `RoleUpdateResult` interno (não exposto via
   HTTP) com os booleans `adminGranted`/`adminRevoked`, calculados comparando as roles
   anteriores (lidas do Mongo antes do save) às novas. O `AdminController` (que detém o JWT
   do ator) usa esses booleans para decidir entre auditar `ROLE_GRANT` ou `ROLE_REVOKE` —
   nunca os dois, nenhum se não houve mudança efetiva em `ADMIN`.

### 5. Teto de paginação nos endpoints de auditoria

`GET /v1/admin/users/{id}/audit-logs` e `GET /v1/admin/audit-logs` aplicam um clamp de
tamanho de página (`AdminService.MAX_AUDIT_PAGE_SIZE = 100`): um `size` maior é truncado, não
satisfeito integralmente. Sem isso, um token ADMIN comprometido drenaria toda a trilha LGPD
numa única requisição (o rate limit do gateway é por requisição, não por volume).

### 6. Deletes administrativos absorvidos do ADR-013

`DELETE /v1/admin/users/{id}` e `DELETE /v1/admin/users/del/{id}` chamam diretamente
`RegisterService.deactivateUser`/`deleteUser` (métodos já existentes, **sem alteração**) e
auditam `SOFT_DELETE_ADMIN`/`HARD_DELETE_ADMIN`. `RegisterService` não ganha método novo —
404 (`DomainEntityNotFound`) e evicção de cache já são tratados internamente por esses
métodos. Não há regra de bloqueio de auto-delete (diferente da auto-revogação de roles): a
spec confirmou que isso não constitui escalonamento de privilégio (é ato terminal, e
self-delete já existe via `/v1/users/delete/me`).

### 7. Gateway — nova rota `/v1/admin/**`

`GatewayRouter.java` ganha a rota `admin-service`, com `tokenRelay()` e rate limit na faixa
**MED** (5 req/s, cap 10) — superfície sensível, poucos operadores esperados, reduz blast
radius em caso de token ADMIN comprometido. Não isenta CSRF (`PATCH .../roles` exige
`X-XSRF-TOKEN` como qualquer outra mutação via sessão BFF).

## Consequências

**Positivas:**
- Fecha a dívida do ADR-001 (visão administrativa de inativos) e do ADR-011 (consulta de
  auditoria) e formaliza o `AdminUserController` previsto pelo ADR-013 (nomeado
  `AdminController` — ver nota de nomenclatura abaixo).
- Mecanismo de promoção/revogação de `ADMIN` auditável via API, eliminando a necessidade de
  manipular o MongoDB diretamente em produção.
- Auto-revogação bloqueada com 409 elimina o risco de lockout operacional sem rota de
  recuperação.

**Negativas / dívida aceita:**
- **Janela do token já emitido:** evictar `authByEmail` garante que o **próximo** token
  emitido reflita as roles novas. Um access token **já emitido** antes da mudança continua
  válido com as roles antigas até expirar — a janela real é o **TTL do access token**, não o
  TTL de 5 min do cache. Revogação ativa de token (introspection/blocklist) está fora de
  escopo. Ver `docs/SECURITY.md`.
- **Cobertura de integração:** o `qa-tester` escreveu `AdminFlowIntegrationTest` (user-service,
  6 testes, Mongo+Redis reais via Testcontainers) e `GatewayAdminRouteIntegrationTest` (gateway,
  5 testes, CSRF/roteamento/rate-limit da rota `/v1/admin/**`), complementando `AdminServiceTest`
  (unit) e `AdminControllerTest` (`@WebMvcTest`). Cobertura completa nos 3 níveis (unit,
  controller, integração) já nesta primeira entrega.
- **Retenção/TTL de `auditLogs` permanece em aberto** (dívida do ADR-011 não tocada por esta
  ADR — só a consulta foi endereçada).
- **Performance do filtro dinâmico** (`MongoTemplate`/`Criteria` em `listAllUsers`) não tem
  índice dedicado para a combinação `active`+`name`+`email`; monitorar em volume alto.

**Nomenclatura — nota de referência cruzada:** o ADR-013 e `docs/CONVENCOES.md` mencionavam
um futuro "`AdminUserController`". O controller efetivamente implementado chama-se
`AdminController` (mesmo papel, nome ajustado). `docs/CONVENCOES.md` e `docs/SERVICOS.md`
foram atualizados com nota de cross-reference esclarecendo a equivalência.

**Consumidores afetados:**
- `gateway` — nova rota, sem alteração de rotas existentes.
- `authorization-server` — consumidor indireto via Feign/`AuthDTO`; depende da evicção de
  `authByEmail` (já coberta) para refletir roles atualizadas no próximo login.
- `UserController`, `RegisterService`, `SearchService`, `AuthenticationService`,
  `InternalUserController` — **inalterados** nesta tarefa.

**Testes:** `AdminServiceTest` (filtros combinados, `updateUserRoles` em todos os ramos —
promoção, revogação de terceiro, sem transição, auto-revogação bloqueada, payload sem `USER`,
role fora do conjunto, alvo inexistente —, clamp de paginação), `AdminControllerTest`
(200/400/403/404/409 em cada endpoint, incluindo verificação `never()` de chamadas de
auditoria nos cenários negativos). Suíte completa do módulo (`mvn verify`) permanece verde,
gate JaCoCo (piso 70%) preservado.

## Alternativas consideradas

- **Pendurar os métodos administrativos em `SearchService`/`RegisterService`:** descartado —
  esses serviços têm hoje contrato 100% não-admin; misturar branches condicionais por role
  degradaria a coesão. `AdminService` dedicado evita esse acoplamento.
- **Normalizar silenciosamente o payload de roles sem `USER` (em vez de rejeitar com 400):**
  descartado — decisão de produto confirmada de manter comportamento explícito e auditável,
  sem efeito colateral surpresa na trilha de auditoria.
- **Checar auto-revogação contra o JWT do ator em vez do estado persistido:** descartado — o
  JWT pode estar stale (roles desatualizadas) por até o TTL do cache `authByEmail`; o estado
  persistido no MongoDB é a única fonte de verdade confiável no momento da operação.
- **`hasRole()` também no gateway (defesa em profundidade):** descartado para esta v1 — manter
  consistência com o padrão atual (autorização por role sempre downstream); registrado como
  decisão explícita, não omissão.
