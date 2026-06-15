# ADR-003: Estado OAuth persistido em PostgreSQL (JDBC repositories do SAS)

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** authorization-server
- **Tarefa relacionada:** ROADMAP item 6 — ADR retroativo

> ADR retroativo: a decisão já está implementada e em produção no blueprint; este
> registro a formaliza para dar rastreabilidade.

## Contexto

O Spring Authorization Server (SAS) precisa persistir três tipos de estado OAuth: os
**registered clients** (configuração dos clientes OAuth2), as **authorizations** (estado de
cada fluxo — incluindo o `authorization_code` emitido e os tokens) e os **consents**
(consentimentos do usuário). Por padrão, o SAS oferece implementações **em memória**
(`InMemoryRegisteredClientRepository` etc.).

Esse padrão in-memory tem dois problemas para um blueprint "pronto para produção":

1. **Não escala horizontalmente.** Com N instâncias do auth-server atrás de um load
   balancer, um `authorization_code` emitido pela instância A precisa ser trocável por token
   em qualquer instância B — impossível se o estado vive na memória de A.
2. **Não sobrevive a restart.** Reiniciar o auth-server descartaria todas as authorizations
   e consents em andamento.

## Decisão

Persistir todo o estado OAuth em **PostgreSQL** (`auth-postgres`) via os repositórios JDBC
do SAS, em `authorizationserver/config/OAuth2ClientConfig.java`:

- `JdbcRegisteredClientRepository` (bean `registeredClientRepository`).
- `JdbcOAuth2AuthorizationService` (bean `oauth2AuthorizationService` — nome distinto de
  `authorizationService`, que é o `UserDetailsService` do projeto; wiring por tipo).
- `JdbcOAuth2AuthorizationConsentService` (bean `oauth2AuthorizationConsentService`).

Detalhes de implementação:

- **Schemas SAS 7.0.3 adaptados** ao Postgres (`blob`→`text`, `timestamp`→`timestamptz`,
  `IF NOT EXISTS`) em `src/main/resources/schema/`, aplicados via `spring.sql.init`
  (`continue-on-error: true`).
- O **`gateway-client` é semeado idempotentemente** na subida: `findByClientId("gateway-client")`
  → `save(gatewayClient())` apenas se ausente, preservando todos os `redirectUri` (inclusive
  o do Swagger UI e o da borda TLS de dev) e scopes (`openid`, `profile`, `users.read`,
  `users.write`).

A **chave de assinatura JWK** é uma decisão correlata mas **separada** ([[ADR-005]]): este
ADR trata do estado OAuth persistido, não da chave que assina os tokens.

Não há mudança de contrato de API externo — é uma decisão de **persistência** interna do
auth-server.

## Consequências

**Positivas:**
- **Escala horizontal** do auth-server: qualquer instância serve qualquer fluxo (estado
  compartilhado no Postgres).
- Estado OAuth (authorizations/consents) **sobrevive a restart**.
- Seeding idempotente do cliente evita divergência entre boots e ambientes.

**Negativas / atenção:**
- Adiciona uma **dependência de PostgreSQL** ao auth-server (já presente no compose como
  `auth-postgres`).
- Exige **manter os schemas adaptados** do SAS — a cada upgrade do Spring Authorization
  Server, conferir se o DDL de referência mudou (`continue-on-error: true` tolera o "já
  existe", mas não cobre mudanças de coluna).
- O `gateway-client` continua sendo semeado por código (não por migração versionada);
  alterações de scope/redirect exigem cuidado com o `findByClientId` (não faz update).

**Testes de regressão:** seeding e persistência cobertos em
`RegisteredClientSeedIntegrationTest` (Postgres real via Testcontainers) e no fluxo OAuth2
de integração do auth-server.

## Alternativas consideradas

- **Repositórios in-memory (default do SAS).** Descartada: não escala horizontalmente nem
  persiste entre restarts — exatamente as forças motrizes desta decisão.
- **Redis como store do estado OAuth.** Descartada: o Redis já é usado para sessão/cache/
  rate-limit; o estado OAuth relacional do SAS tem suporte JDBC de primeira classe e
  semântica transacional mais adequada que um KV com TTL.
- **Outro RDBMS (MySQL etc.).** Não há impedimento técnico; PostgreSQL foi escolhido pela
  adequação dos tipos (`timestamptz`, `text`) e por já ser o banco relacional do stack.
