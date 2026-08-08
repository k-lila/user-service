# ADR-024: Elasticidade — piso mínimo por default, escala por eixo declarado

- **Status:** aceita
- **Data:** 2026-08-07
- **Serviço alvo:** topologia (docker-compose, config-server, infra) — sem mudança de contrato de API
- **Tarefa relacionada:** checkpoint de blueprint (elasticidade)

## Contexto

O projeto se apresenta como "blueprint de um sistema de usuários", mas a topologia era **fixa nas
duas direções**.

**Não encolhia.** O `docker compose up` sem argumento subia 26 serviços — Mongo replica set de 3
nós, Redis com 3 data nodes e 3 sentinels, config-server e discovery-server em pares nomeados —
consumindo ~10 GB. Um blueprint que só sobe nessa escala não é ponto de partida para ninguém.

**Não crescia com segurança.** Uma auditoria da camada de aplicação mostrou que ela **já era
replicável**: nenhum estado em memória (cache, sessão, rate limit, lockout e epoch de revogação
vivem no Redis; estado OAuth no Postgres), `@Scheduled` com lock `SETNX` distribuído, seed do
`gateway-client` protegido por índice único (ADR-022), `instanceId` do Eureka único por container.
Um `--dry-run` com `--scale user-service=3 --scale gateway=2` planejava as réplicas sem conflito.
Mas três coisas quebravam ou cegavam ao escalar:

1. **Pool JDBC no default de 10 contra `max_connections=100`.** Medido no ambiente real: 10
   conexões do pool + 6 de exporter/internas, ~84 livres. A ~9ª réplica de auth-server o Postgres
   recusa conexão, com o sintoma (`FATAL: sorry, too many clients already`) aparecendo no
   auth-server, longe da causa, e **só sob a escala que deveria estar ajudando**.
2. **Prometheus com `static_configs` por nome de serviço.** `user-service:8181` resolve para UMA
   réplica por scrape (round-robin do DNS do Docker): a partir de N > 1 as séries alternam entre
   processos a cada 5s e o `dashboardJVM` soma heap de containers distintos como se fosse o mesmo.
   Escalava-se às cegas.
3. **`upstream` estático no `config-lb`.** O nginx resolve os nomes do bloco `upstream` no start e
   **recusa iniciar** se algum não resolver. Com `config-server-1`/`config-server-2` hardcoded,
   subir uma única instância derrubava o `config-lb` — o piso mínimo era literalmente impossível.

## Decisão

Adotar **piso mínimo por default e crescimento sob comando**, governado por um princípio único:

> **Encolher é remover nó; crescer é adicionar nó — nunca reconfigurar cliente.**

É esse princípio que decide o formato do piso: o Mongo mínimo é um **replica set `rs0` de um
membro** (não standalone) e o Redis mínimo é **um nó com um Sentinel** (não Redis direto). Assim a
`MONGODB_URI` (`replicaSet=rs0`) e o `spring.data.redis.sentinel.*` dos clientes são **idênticos**
nos dois extremos, e o ADR-008 (`requirepass` + `masterauth` + `sentinel auth-pass`) vale sem
emenda. O custo de um piso degenerado é não ter failover — o que é correto por construção: não há
para onde falhar.

**Três eixos de escala, declarados** (catalogados em `docs/BLUEPRINT.md § E`):

| Eixo | Componentes | Como cresce |
|---|---|---|
| **Replicável** | gateway, user-service, authorization-server, notification-service, **config-server**, interface | `--scale <svc>=N` |
| **Réplica nomeada** | discovery-server, mongo, redis, redis-sentinel | `--profile ha` |
| **Singleton** | auth-postgres, config-lb, cloudflared, observabilidade | não escala |

**Mudanças concretas:**

- **`config-server-1`/`config-server-2` → serviço único `config-server`.** Sendo stateless,
  pertence ao eixo replicável; o `config-lb` passa a resolver por DNS (`resolver 127.0.0.11` +
  `proxy_pass` sobre variável, o mesmo padrão de `login-interface/nginx.conf`), com
  `proxy_next_upstream` preservando o failover que o bloco `upstream` dava.
- **`profiles: ["ha"]`** em `mongo-2/3`, `redis-2/3`, `redis-sentinel-2/3`, `discovery-server-2`.
- **`mongo-init` vira reconciliador idempotente** (`infra/mongo/rs-reconcile.sh`).
- **Pools explícitos:** `AUTH_DB_POOL_SIZE` (8) e `MONGO_MAX_POOL_SIZE` (50).
- **Prometheus por réplica:** `dns_sd_configs` para os quatro apps e para o config-server.
- **Perfil de recurso parametrizável:** `APP_CPUS` / `APP_MEM_LIMIT` / `APP_MEM_RESERVATION`.

### Invariante de implementação: `depends_on` nunca aponta para dentro do profile

Nenhum serviço fora do profile `ha` pode declarar `depends_on` para um serviço dentro dele. O
comportamento do Compose para `depends_on` → serviço com profile inativo **variou entre versões**;
obedecer a essa regra torna a questão irrelevante em vez de apostar na versão instalada.

Isso obrigou a remover esperas que, revistas, nunca deveriam ter existido: `redis-sentinel-1`
esperava `redis-2/3` (o Sentinel descobre réplicas sozinho pelo `INFO` do master), o
`mongodb-exporter` esperava `mongo-2/3` (tem `--discovering-mode`), e os quatro apps esperavam
`discovery-server-2`/`redis-sentinel-2/3`/`mongo-2/3` quando um de cada basta para subir.

### Por que o `rs.initiate` não bastava

`rs.initiate` roda **uma vez por volume**: num volume já iniciado devolve `AlreadyInitialized` e
não faz nada. Se o piso mínimo subisse com um membro, ligar `--profile ha` depois **não
adicionaria membro nenhum** — o replica set ficaria eternamente com um nó enquanto dois containers
Mongo ociosos rodariam ao lado. Crescer exige `rs.reconfig`.

O script decide os membros **por resolução DNS**, não por variável: um nó que não está no ar não
tem registro no DNS do Compose. Assim `--profile ha` sozinho basta — sem env acoplada ao profile,
que é a classe de bug já registrada neste repositório (a varredura de 2026-08-06 encontrou 19
variáveis documentadas e inertes). E **só cresce, nunca encolhe**: remover membro com base em
lookup que falhou seria perigoso — uma falha transitória de DNS num restart derrubaria o quorum.
Encolher é operação manual e deliberada (`rs.remove`).

### Emenda (2026-08-07): a guarda de maioria

"Só cresce" estava certo, mas deixava um **modo de falha silencioso** que só apareceu ao testar o
encolhimento. A config do `rs0` vive no volume: depois de um ciclo `--profile ha` ela tem 3 membros
e continua com 3 quando mongo-2/3 somem. Sozinho numa config de 3 votantes, mongo-1 não alcança
maioria, assume SECONDARY e **recusa escrita** — com leitura funcionando e healthcheck verde. O
`rs-reconcile.sh` chegava nesse estado, calculava `missing = []` e **saía com sucesso**,
ratificando-o. Medido no ambiente de dev em 2026-08-07: 3 membros configurados, 1 saudável,
`isWritablePrimary: false`, `insertOne` falhando com `NotWritablePrimary`.

O script agora compara os membros **configurados** com os **alcançáveis** e falha quando estes não
formam maioria, imprimindo os `rs.remove` exatos a executar. Falhar em vez de avisar é deliberado:
`user-service` depende do job com `condition: service_completed_successfully`, então o `exit 1`
impede a aplicação de subir contra um Mongo somente-leitura e aceitar cadastros que morreriam em
500. O caso transitório se auto-resolve pelo mesmo `restart: on-failure` que já cobria a corrida de
startup. O script **continua sem nunca remover membro sozinho** — o racional acima fica intacto; a
guarda só transforma silêncio em diagnóstico. Runbook do encolhimento em `docs/CONFIG.md`.

**Encolhimento verificado ponta a ponta** (mesma data, sobre dado real): `rs.remove` dos dois
membros → `--profile ha down` (sem `-v`) → `up` do piso. Preservados: contagem de `users` e
`auditLogs`, o titular semeado com o mesmo `_id`, a leitura da credencial pelo canal interno
(HTTP 200), e o estado OAuth no Postgres (`registered_client`/`authorization`/`consent` = 1/1/0,
inalterados). Escrita pós-encolhimento: `POST /v1/users/register` → 201. **Não** preservado: o
Redis, que não tem volume nomeado — sessões, cache e contadores de rate limit são perdidos em
qualquer `down`, não só neste.

## Consequências

**Positivas.** O piso cai de 26 para **19 serviços** (13 de plataforma + 6 de observabilidade). O
eixo replicável ganha `config-server`, que antes exigia editar arquivo para mudar de quantidade.
Métricas passam a discriminar réplicas. E o ganho maior da replicação da camada de aplicação não é
vazão: é **deploy sem downtime** — os knobs de convergência já calibrados (`stop_grace_period: 35s`,
`timeout-per-shutdown-phase: 30s`, lease Eureka de 30s, cache do LoadBalancer de 5s) só viabilizam
rolling restart com N > 1.

**Negativas e limites.** O piso mínimo **não é HA**: um nó Mongo e um Redis sem failover. Está
registrado em `docs/SECURITY.md` porque a confusão entre "piso enxuto" e "redundante" é fácil e
cara. No piso mínimo o `discovery-server-1` registra **~11 WARN/min** de replicação contra um peer
que não existe — barulho deliberado, ver a nota abaixo. Não escalam com as réplicas: o
rate limit (global no Redis — replicar não levanta o teto por cliente) e a escrita (um primário
Mongo, um Postgres). O `auth-postgres` segue singleton e é o SPOF do login.

**Elasticidade medida** (não só registrada — a distinção importa, ver o erro do Eureka abaixo):
com `user-service` em 3 réplicas, o gateway distribuiu **10/10/10** em 30 requisições;
`authorization-server` em 2 recebeu ~16/19 em 40; `config-server` em 2, atrás do `config-lb`,
+22/+38 em 40. O caminho via Eureka é round-robin exato; o via DNS (nginx) é enviesado, porque a
ordem dos endereços fica fixa durante a janela do `valid=10s`. Uma réplica nova leva **~25s** para
receber tráfego (14s de subida + ~11s de convergência: fetch do Eureka a 10s + cache do
LoadBalancer a 5s) — escalar antes do pico, não durante. Encolher com `docker compose scale`
custou **0 falhas em 45** requisições (parada graciosa: SIGTERM → 35s → desregistro); `docker stop`
abrupto custou **3 em 60**, a janela do cache DNS do nginx.

**Verificação.** `compose-validate` no CI ganhou três asserções: as quatro combinações de compose
válidas; o delta piso→`ha` exatamente igual aos 7 nós esperados (senão um `profiles:` esquecido faz
o piso voltar a 26 sem ninguém notar); e ausência de `container_name`/`ports:` nos serviços do eixo
replicável (qualquer um dos dois faz a 2ª réplica falhar no bind).

### Erro corrigido na verificação: auto-referência do `discovery-server-1`

A primeira versão desta decisão apontava o `EUREKA_PEER_URL` do `discovery-server-1` **para si
mesmo**, para não registrar falha de replicação contra um peer inexistente no piso mínimo, deixando
o `discovery-server-2` apontar para o `-1` e se manter atualizado por *fetch* — descrito como
"standby quente". **Está errado, e a verificação com a stack de pé provou.**

O *fetch* client-side de um servidor Eureka popula o cache que ele usa **como cliente**, não o
registry que ele **serve** a terceiros. Replicação de peer no Eureka é **push**: sem o `-1`
apontando para o `-2`, o nó 2 nunca recebe nada. Medido no piso `ha` com 2 gateways:

```
discovery-server-1 → AUTHORIZATION-SERVER=1, GATEWAY=2, USER-SERVICE=2, ...
discovery-server-2 → GATEWAY=2, USER-SERVICE=1          ← sem AUTHORIZATION-SERVER
```

Todo gateway que buscasse o registry no `-2` respondia **503 em `/login`**
(`No servers available for service: authorization-server`) — ou seja, o piso `ha` estava
**quebrado pela metade**, com o sintoma dependendo de qual nó Eureka a réplica sorteasse.

A correção é apontar para o par sempre. O custo é WARN de replicação no piso mínimo — **medido em
~11 linhas/min** e deliberadamente **não silenciado**: é exatamente a mensagem que denuncia falha
de replicação no piso `ha`, e suprimi-la esconderia esta classe de bug em vez de corrigi-la. A
polaridade é o que importa: **um default errado quebra o sistema; um default barulhento só
incomoda.** Quem roda o piso mínimo e quer silêncio faz opt-in explícito
(`EUREKA_PEER_URL=http://discovery-server-1:9091/eureka`), seguro **só** com um nó.

Lição transferível: "componente sobe healthy" não é evidência de que o cluster funciona. O
`discovery-server-2` estava `healthy` o tempo todo, servindo um registry incompleto.

## Alternativas consideradas

- **Autoscaler lendo o Prometheus.** Rejeitado: componente novo com estado e modos de falha
  próprios (flapping; escalar durante um outage do Mongo e piorar), e num host único o teto de RAM
  chega antes do benefício. Elasticidade aqui é **sob comando**.
- **Migrar para Docker Swarm** (`deploy.replicas`, VIP por serviço). Rejeitado: reescreveria os três
  compose — a seção de recursos usa `cpus`/`mem_limit` justamente por **não** ser Swarm —, além de
  secrets e healthchecks. É outro projeto, não um checkpoint.
- **Remover o `config-lb`** e apontar os clientes direto a `config-server:8888` (um container a
  menos). Rejeitado: troca failover HTTP-aware por DNS round-robin, e com `fail-fast: true` em
  todos os `*.yml` um cliente que sorteie a réplica morta falha no boot.
- **Mongo standalone e Redis sem Sentinel no piso** (piso ainda menor). Rejeitado: quebraria a
  `MONGODB_URI` e tornaria a config de Sentinel condicional por profile — o caminho de volta ao HA
  deixaria de ser "adicionar nó" e viraria "reconfigurar cliente", contra o princípio.
- **`MONGO_RS_MEMBERS` por variável de ambiente** em vez de descoberta por DNS. Rejeitado: acopla
  o profile a uma env que se esquece de setar junto, exatamente o padrão de falha das variáveis
  inertes.
- **Leitura em secundário (`readPreference=secondaryPreferred`)** para aproveitar os nós do piso
  `ha`. **Adiado, não rejeitado** — mas nunca global: o lag de replicação faria
  `/internal/users/email/{email}` devolver 404 logo após o cadastro, e esse 404 **conta no lockout**
  por desenho do ADR-021. Atraso de replicação viraria conta bloqueada por 15 minutos. Se for
  feito, só nas leituras que toleram staleness (feed de auditoria, listagem administrativa), via
  `@ReadPreference` no repositório.
