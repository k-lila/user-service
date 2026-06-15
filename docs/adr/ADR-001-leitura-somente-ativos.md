# ADR-001: Endpoints de leitura retornam apenas usuários ativos

- **Status:** aceita
- **Data:** 2026-06-13
- **Serviço alvo:** user-service
- **Tarefa relacionada:** hardening do blueprint (leitura somente de usuários ativos)

## Contexto

O user-service implementa **soft-delete**: `deactivateUser` marca `active=false` em vez de
remover o documento (ver _Semânticas de DELETE_ em [SERVICOS.md](../SERVICOS.md)). Até esta
decisão, porém, os endpoints de **leitura** não filtravam por `active`:

- `GET /v1/users` (`searchAll`) usava `findAll`, retornando também os soft-deleted.
- `GET /v1/users/{id}` (`searchById`) e `GET /v1/users/email/{email}` (`searchByEmail`)
  retornavam o usuário mesmo com `active=false`.
- `GET /v1/users/me` (que delega a `searchById`) idem.

Todos esses endpoints exigem apenas `ROLE_USER` (não são admin-only). Logo, qualquer
usuário autenticado conseguia recuperar contas desativadas por ID ou e-mail — um vazamento
de dados de contas que deveriam estar "removidas" da perspectiva de leitura, e uma
inconsistência com a própria semântica de soft-delete do projeto. O comportamento nunca
fora registrado como decisão (diferente da clareza do resto do projeto), o que motivou
formalizá-lo.

O canal interno de autenticação (`AuthenticationService.getUserByEmail`, consumido pelo
authorization-server via Feign) **já** filtrava inativos — esta decisão apenas alinha os
endpoints de leitura públicos ao mesmo princípio.

## Decisão

Os endpoints de leitura passam a expor **somente usuários ativos** (`active=true`):

- `searchAll` usa `IUserRepository.findByActiveTrue(Pageable)` em vez de `findAll`.
- `searchById` e `searchByEmail` tratam usuário inativo como inexistente: lançam
  `DomainEntityNotFound` → **404** (mesma resposta de "não existe", sem distinguir
  inativo de inexistente para não vazar a existência de contas desativadas).

Não há versionamento de path: o contrato permanece em `/v1/`. A mudança é de
**comportamento** dentro do contrato existente (um recurso soft-deleted deixa de ser
legível), considerada correção de coerência da semântica de soft-delete, não uma quebra de
schema ou de formato de resposta.

## Consequências

**Positivas:**
- Semântica de soft-delete coerente: um usuário desativado some das leituras.
- Fecha o vazamento de contas desativadas a qualquer `ROLE_USER`.
- Alinha os endpoints de leitura ao filtro que o canal de autenticação já aplicava.

**Negativas / atenção:**
- `GET /v1/users/me` passa a retornar **404** para uma conta desativada que ainda detenha
  um JWT válido emitido antes da desativação (edge raro: o login já bloqueia inativos).
  Comportamento aceitável — a conta não deve operar.
- Não há mais um endpoint que liste/recupere inativos. Se no futuro for preciso uma visão
  administrativa de contas desativadas (auditoria), será um **novo** endpoint admin-only,
  registrado em ADR próprio.
- Consumidores que dependiam de ler inativos (não há nenhum hoje) seriam afetados.

**Testes de regressão:** cobertos em `SearchServiceTest` (unitário: inativo por ID/e-mail →
`DomainEntityNotFound`) e `UserFlowIntegrationTest` (integração: após `deactivateUser`, o
usuário some de `searchById`/`searchByEmail`/`searchAll`).

## Alternativas consideradas

- **Manter o comportamento e documentá-lo como visão administrativa intencional.**
  Descartada: os endpoints são `ROLE_USER` (não admin), então "visão administrativa" não se
  sustentaria sem antes restringir o acesso; e contraria a expectativa padrão de soft-delete.
- **Filtrar apenas em `searchAll`, mantendo `searchById`/`searchByEmail` retornando inativos.**
  Descartada: deixaria o vazamento por ID/e-mail aberto — exatamente o caminho mais direto
  para recuperar uma conta específica desativada.
- **Hard-delete em vez de soft-delete.** Fora de escopo: o soft-delete é uma decisão de
  design deliberada do projeto (preserva histórico); esta ADR o torna coerente, não o remove.
