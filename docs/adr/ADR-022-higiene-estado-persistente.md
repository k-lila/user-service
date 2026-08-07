# ADR-022: Higiene do estado persistente — purga do estado OAuth, retenção da trilha de auditoria e seed do cliente sob concorrência

- **Status:** aceita
- **Data:** 2026-08-06
- **Serviço alvo:** authorization-server · user-service
- **Tarefa relacionada:** Bloco 3 do plano de correções (análise de capacidade/escala de 2026-08-05)

## Contexto

Três defeitos independentes na superfície, mas com a mesma forma: **estado persistente que cresce
sem limite ou que é escrito sob uma checagem que a concorrência atravessa**. Nenhum se manifesta em
desenvolvimento; todos se manifestam exatamente no cenário que este projeto existe para exercitar —
mais de uma instância, e tempo passando. Os três foram confirmados no stack em execução, não por
leitura de código.

### 1. `oauth2_authorization` cresce uma linha por login, para sempre

O `JdbcOAuth2AuthorizationService` do Spring Authorization Server grava uma linha por autorização e
**nunca** apaga nenhuma. O access token expira, o refresh token expira, e a linha permanece. Medido
no Postgres do deploy atual:

```
 access_expirado | refresh_expirado | total
               8 |                8 |     8
```

Oito de oito linhas completamente mortas, e não existe um único `@Scheduled` no módulo inteiro. A
tabela é, na prática, um log de autenticações que ninguém lê, encarecendo backup, restore e o
índice primário — e o custo é proporcional ao sucesso do sistema.

### 2. `auditLogs` sem retenção e sem índice para o feed geral

```
auditLogs:
  idx: _id_
  idx: target_ts_idx {"targetUserId":1,"timestamp":-1}
```

Dois problemas distintos sob o mesmo teto:

- **Sem TTL.** A coleção cresce indefinidamente. O [ADR-011](ADR-011-trilha-auditoria-dado-pessoal.md)
  registrou isso como dívida aceita, num momento em que cada operação gravava uma entrada. O fix do
  **G13** mudou a aritmética: `GET /v1/admin/users` passou a gravar **uma entrada por titular
  retornado**, até 100 por requisição. A dívida foi contraída sob premissa que deixou de valer.
- **Feed geral sem índice utilizável.** `GET /v1/admin/audit-logs` é um `findAll` ordenado por
  `timestamp`, sem filtro por titular. O índice existente tem `targetUserId` no prefixo e portanto
  não o atende: a ordenação vai para memória, e o MongoDB **aborta** a query ao ultrapassar 32 MB de
  sort. É uma falha que não aparece em teste e só surge com a coleção já grande — isto é, quando a
  trilha é mais necessária.

### 3. O seed do `gateway-client` é um check-then-act sem rede de proteção

`OAuth2ClientConfig` semeia o cliente com `findByClientId(...) == null → save(...)`. O schema
oficial do SAS, adotado aqui, define `PRIMARY KEY (id)` sobre o UUID e **nenhuma constraint sobre
`client_id`**. Com N instâncias subindo juntas, todas leem "ausente" e todas gravam: N linhas com o
mesmo `client_id`, `id` distinto e — porque o BCrypt salga a cada chamada — hash distinto do mesmo
segredo.

O que torna isso perigoso é que **nada quebra**. Todos os hashes validam o mesmo segredo, então o
login continua funcionando; `findByClientId` passa a devolver `result.get(0)` de uma query sem
`ORDER BY`, isto é, uma linha arbitrária. A duplicata só aparece quando alguém tentar reconciliar
`redirectUri` e descobrir que há dois clientes — possivelmente meses depois.

Verificado no bytecode do `JdbcRegisteredClientRepository` 7.0.3: o método `save` chama
`assertUniqueIdentifiers`, que lança `IllegalArgumentException` ao encontrar `client_id` duplicado.
**Isso não resolve a corrida** — é ele próprio um check-then-act, e duas instâncias concorrentes o
atravessam juntas. Serve apenas para adiantar o erro no caso sequencial.

## Decisão

### 1. Purga agendada do estado OAuth (`OAuthStatePurgeService`)

`@Scheduled` no authorization-server (que ganha `@EnableScheduling`, até então o único módulo de
domínio sem agendamento) apagando em lote as autorizações totalmente expiradas.

**O critério de morte é o `GREATEST` das seis colunas de expiração**, não uma delas. Filtrar por
`access_token_expires_at` — a escolha óbvia — apagaria autorizações cujo refresh token ainda é
válido, deslogando usuários ativos a cada ciclo. O `COALESCE(..., 'epoch')` trata coluna nula como
"expirou há muito", porque nulo aqui significa que aquele grant não foi usado nesta autorização, e
não que ele nunca expira. Isso tornaria uma linha com *todas* as colunas nulas elegível, daí a
disjunção `IS NOT NULL` que exige ao menos uma expiração real antes de considerar a linha morta:
sem ela, o `DELETE` poderia alcançar estado vivo.

O `LIMIT` dentro do subselect limita a transação. Numa base que acumulou meses de estado, um
`DELETE` único seguraria lock sobre a tabela inteira; o ciclo seguinte continua de onde parou.

**Sem índice para o predicado**, deliberadamente. O filtro é uma expressão sobre seis colunas, que
só um índice de expressão atenderia — e, uma vez que a purga roda, a tabela fica *limitada*.
Varredura sequencial a cada 6h sobre tabela contida é barata; o índice seria custo de escrita
permanente para ganho nenhum.

### 2. Retenção da trilha de auditoria: 180 dias, no padrão *expire-at*

Campo `purgeAt` em `AuditLog` com índice TTL `expireAfterSeconds = 0`, preenchido na escrita a
partir de `app.audit.retention` — o mesmo padrão já usado em `NotificationOutbox`, e não um TTL
fixo no índice. Um TTL fixo prenderia a retenção ao valor usado na criação do índice: mudá-la
exigiria `collMod`, não configuração.

Entradas gravadas antes desta mudança não têm o campo, e o TTL do MongoDB **ignora** documento sem
o campo indexado — o histórico anterior nunca é apagado. É o lado seguro numa trilha de
conformidade: dado apagado não volta.

Índice `ts_idx` sobre `{timestamp: -1}` para o feed geral.

### 3. Índice único sobre `client_id`, e seed tolerante à violação

`CREATE UNIQUE INDEX IF NOT EXISTS uk_oauth2_registered_client_client_id` acrescentado ao schema.
`CREATE UNIQUE INDEX IF NOT EXISTS` é idempotente **e** se aplica a tabela já existente — diferente
de `CREATE TABLE IF NOT EXISTS`, que não revisita o schema de uma tabela criada antes desta
mudança.

`seedGatewayClient` absorve a violação para que o perdedor da corrida siga a subida em vez de
abortar o contexto. A mesma corrida chega por **duas** exceções, conforme o instante em que a outra
instância commitou: `IllegalArgumentException` (o `assertUniqueIdentifiers` do SAS já enxerga a
linha) ou `DuplicateKeyException` (ambas passaram por aquele check e foi o índice único que rejeitou
o segundo `INSERT`). O seed relê o registro para confirmar que foi a corrida — se o cliente não
estiver lá, a exceção tem outra causa e é propagada, senão o serviço subiria sem cliente OAuth
nenhum.

**A ordem importa:** a constraint é o que fecha a brecha; o `catch` é apenas o que a torna benigna.
Um `catch` sem a constraint seria só mais uma checagem em corrida.

## Consequências

**Positivas**
- `oauth2_authorization` passa de crescimento monotônico a tamanho limitado pela retenção.
- A trilha de auditoria ganha limite superior de tamanho, e o feed geral deixa de ter uma falha
  latente que só apareceria em produção madura.
- Duplicar o `gateway-client` torna-se impossível, não improvável.

**Negativas / dívida**
- **Retenção é decisão de conformidade, não técnica.** 180 dias foi escolha explícita; se o prazo
  legal aplicável for maior, `AUDIT_LOG_RETENTION` tem de ser ajustado **antes** que a primeira
  entrada expire — depois disso o dado não volta.
- O default de cada nova chave passa a existir em dois lugares (YAML do config-server e
  `docker-compose.yml`), com risco de divergência. É o preço já aceito no Bloco 2 para que a
  variável não seja documentada e inerte.
- A purga do estado OAuth roda sem métrica: hoje o acompanhamento é só pelo log `| OAUTH-PURGE |`.
- Numa base que **já** tenha duplicatas de `client_id`, a criação do índice único falha e o
  `continue-on-error: true` do `spring.sql.init` a engole em silêncio. Deduplicar à mão nesse caso.

## Alternativas consideradas

- **TTL fixo no índice de `auditLogs` (`expireAfterSeconds = N`)** — descartado: prende a retenção
  ao valor da criação do índice, e mudá-la vira operação de banco.
- **Purgar o estado OAuth por `access_token_expires_at`** — descartado: apaga autorizações com
  refresh token vivo, deslogando usuários ativos.
- **Índice de expressão para o predicado da purga** — descartado: custo de escrita permanente sobre
  uma tabela que a própria purga mantém pequena.
- **Confiar no `assertUniqueIdentifiers` do SAS** — descartado: é um check-then-act, e portanto não
  cobre justamente o cenário concorrente que é o problema.
- **Trocar a paginação do feed de auditoria para cursor** — fora de escopo por decisão explícita: é
  mudança de contrato de dois endpoints para otimizar paginação profunda que ninguém faz numa
  trilha administrativa. O índice resolve o problema real (o sort em memória).
- **Cache local do epoch de revogação** — avaliado junto a estes três e **descartado**: trocaria um
  controle de segurança recém-fechado ([ADR-017](ADR-017-revogacao-ativa-token.md)) — alargando a
  janela de revogação de milissegundos para o TTL do cache — por uma economia de latência que a
  carga atual não justifica.

## Relação com outros ADRs

- Emenda a dívida registrada no [ADR-011](ADR-011-trilha-auditoria-dado-pessoal.md) ("sem TTL").
- Complementa o [ADR-003](ADR-003-estado-oauth-postgresql.md), que moveu o estado OAuth para o
  Postgres sem tratar seu ciclo de vida.
- Endurece o seed descrito no [ADR-003](ADR-003-estado-oauth-postgresql.md) e referido no
  [ADR-005](ADR-005-chave-jwk-persistente.md).
