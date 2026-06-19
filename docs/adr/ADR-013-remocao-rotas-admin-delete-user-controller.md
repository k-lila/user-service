# ADR-013: Remoção das rotas de deleção administrativa do UserController

- **Status:** aceita
- **Data:** 2026-06-18
- **Serviço alvo:** user-service
- **Tarefa relacionada:** evolução do domínio — preparação para `AdminUserController` dedicado

## Contexto

O ADR-001 e `docs/CONVENCOES.md` documentavam três rotas DELETE como invariante intencional no
`UserController`:

- `DELETE /v1/users/{id}` (ADMIN) — soft-delete de outro titular
- `DELETE /v1/users/del/{id}` (ADMIN) — hard-delete de outro titular
- `DELETE /v1/users/remove/me` (USER) — soft-delete do próprio usuário

Decisão de produto: o `UserController` deve deixar de tratar operações administrativas sobre
outros titulares. Essas operações migrarão para um `AdminUserController` dedicado, com contrato e
preocupações de autorização próprias — tarefa futura, fora do escopo desta mudança. Em
compensação, o usuário autenticado ganha a opção de hard-delete da própria conta, simétrica ao
soft-delete que já existia em `/remove/me`.

Restrições do projeto observadas: roles fixas `USER`/`ADMIN`; DELETE com semânticas distintas e
intencionais (ADR-001) — soft-delete preserva o registro, hard-delete remove definitivamente;
trilha de auditoria LGPD (ADR-011) deve continuar registrando toda mutação.

## Decisão

1. Remover do `UserController` os métodos mapeados em `DELETE /v1/users/{id}` (ADMIN, soft-delete
   de outro titular) e `DELETE /v1/users/del/{id}` (ADMIN, hard-delete de outro titular).
2. Adicionar `DELETE /v1/users/delete/me` (`@PreAuthorize("hasRole('USER')")`), espelhando o
   padrão de `/remove/me`: extrai `userID` do claim JWT, chama `registerService.deleteUser`
   (método de serviço já existente, reaproveitado sem alteração), audita com a nova ação
   `AuditAction.HARD_DELETE_SELF`.
3. O enum `AuditAction` ganha o valor `HARD_DELETE_SELF`. Os valores `SOFT_DELETE_ADMIN` e
   `HARD_DELETE_ADMIN` **permanecem** no enum, reservados e sem rota ativa que os produza — serão
   consumidos pelo futuro `AdminUserController`. **Nota (2026-06-18):** esse controller futuro
   chegou como `AdminController` — ver [ADR-014](ADR-014-admin-controller-gestao-roles-auditoria.md),
   que ativa as rotas `DELETE /v1/admin/users/{id}` e `.../del/{id}` produzindo essas ações.
4. Não há mudança de schema (MongoDB) nem de formato de resposta (`UserResponseDTO`); a mudança é
   estritamente de superfície de rotas no `UserController`.

## Consequências

- **Positivo:** `UserController` fica coeso — só trata operações sobre o próprio usuário
  autenticado (`/me`, `/remove/me`, `/delete/me`, registro). Usuário ganha simetria entre
  soft-delete e hard-delete da própria conta.
- **Negativo / dívida aceita:** não há, temporariamente, via administrativa de deleção (soft ou
  hard) de outro titular até a chegada do `AdminUserController`. Aceito como dívida consciente —
  rastreada nesta ADR. **Fechada** pelo [ADR-014](ADR-014-admin-controller-gestao-roles-auditoria.md)
  (`AdminController`).
- **Consumidores afetados:** nenhum endpoint público remanescente muda de contrato (`/remove/me`
  inalterado); os dois endpoints removidos eram usados apenas por ADMIN — se algum cliente externo
  os chamava, passa a receber 404 (rota inexistente) em vez de 204/403. Nenhum serviço interno
  (gateway, auth-server) dependia dessas rotas via Feign.
- **Testes:** `UserControllerTest` perde os casos de `/{id}` e `/del/{id}` (incluindo auditoria
  `SOFT_DELETE_ADMIN`/`HARD_DELETE_ADMIN` por essa via) e ganha os equivalentes para
  `/delete/me` (sucesso, 401, 403, 404, auditoria `HARD_DELETE_SELF`). `RegisterServiceTest`,
  `AuditServiceTest` e os testes de integração permanecem inalterados — testam a camada de
  serviço/auditoria de forma agnóstica à rota.

## Alternativas consideradas

- **Manter os 3 endpoints existentes e apenas adicionar um 4º (`/delete/me`):** descartado — não
  atende à decisão de produto de tirar do `UserController` a responsabilidade administrativa sobre
  outros titulares; perpetuaria a mistura de escopos (self-service vs. admin) no mesmo controller
  até o `AdminUserController` chegar, sem ganho real nesse meio-tempo.
- **Remover `SOFT_DELETE_ADMIN`/`HARD_DELETE_ADMIN` do enum `AuditAction` agora:** descartado —
  esses valores serão reaproveitados pelo `AdminUserController` futuro; remover e recriar geraria
  churn sem necessidade, e o enum não tem custo de manter valores não utilizados.
