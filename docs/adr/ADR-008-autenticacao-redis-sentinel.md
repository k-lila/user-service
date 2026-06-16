# ADR-008: Autenticação no Redis e Sentinel (senha uniforme nos 6 nós)

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** gateway · authorization-server · user-service · infra Redis
- **Tarefa relacionada:** TASK-P4-REDIS-AUTH

## Contexto

Na v1 do blueprint o Redis (3 data nodes + 3 sentinels) operava **sem autenticação** —
gap registrado em `docs/SECURITY.md` com mitigação única de "portas nunca publicadas no
compose base". Essa mitigação é válida em dev (compose base prod-safe), mas insuficiente
em produção real: qualquer processo na rede interna Docker (ou um container comprometido)
pode ler e gravar o Redis sem credencial.

Restrições relevantes:
- Redis 7 (imagem `redis:7` = 7.4.8): `requirepass` em sentinels habilita autenticação
  tanto sentinel-to-client quanto sentinel-to-sentinel (quórum). Os sentinels se autenticam
  entre si com a **própria senha do sentinel** (`requirepass`), que deve ser a mesma em
  todos — fato verificado ao vivo contra a imagem.
- `spring.data.redis.sentinel.password` é a propriedade correta no Spring Boot 4.0.3 para
  autenticar o cliente Java **nos sentinels** (distinta de `spring.data.redis.password`, que
  autentica nos data nodes).
- O redis-exporter opera em modo multi-target (alvo vem do Prometheus via `?target=`) e
  usa `REDIS_PASSWORD` como env var nativa para autenticação global — verificado
  empiricamente contra `oliver006/redis_exporter:v1.62.0`: esta var autentica tanto no
  modo `/metrics` (addr único) quanto no modo `/scrape?target=` (multi-target). Não segue
  o prefixo convencional `REDIS_EXPORTER_` para esta variável específica.
- `REDISCLI_AUTH` é honrado pelo `redis-cli` da imagem `redis:7`, resolvendo o `NOAUTH`
  nos healthchecks sem expor a senha no comando shell visível por `ps`.
- O `sentinel.conf` é montado `:ro` e copiado para `/tmp` antes de o sentinel iniciar
  (invariante documentada em `docs/CONVENCOES.md` — o sentinel regrava o arquivo em
  runtime). A senha não deve ser commitada no arquivo versionado.

## Decisão

**Senha uniforme** nos 6 nós Redis: os 3 data nodes recebem `--requirepass` e
`--masterauth` com o mesmo valor; os 3 sentinels recebem `requirepass` e
`sentinel auth-pass mymaster` com o mesmo valor. A senha é gerida pela variável de ambiente
`REDIS_PASSWORD`, sem default no compose (fail-fast na subida se ausente).

### Detalhes de implementação

**Data nodes (redis-1/2/3):**
- `command: redis-server --requirepass ${REDIS_PASSWORD} --masterauth ${REDIS_PASSWORD}`
- `masterauth` é obrigatório **inclusive no master atual (redis-1)**: após um failover o
  master anterior reintegra como réplica e precisa autenticar-se no novo master — sem
  `masterauth` o PSYNC falha silenciosamente com `NOAUTH`.
- Healthcheck via `REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli ping | grep -q PONG`.
- `REDIS_PASSWORD` exposto como env no container para o healthcheck (não há outra forma de
  referenciar a variável no campo `test` sem passá-la como env do serviço).

**Sentinels (redis-sentinel-1/2/3):**
- O `command` segue o padrão copy-to-`/tmp` já existente, estendido com dois `echo`:
  ```
  cp /etc/redis/sentinel.conf /tmp/sentinel.conf &&
  echo 'requirepass '$$REDIS_PASSWORD >> /tmp/sentinel.conf &&
  echo 'sentinel auth-pass mymaster '$$REDIS_PASSWORD >> /tmp/sentinel.conf &&
  exec redis-sentinel /tmp/sentinel.conf
  ```
- Nenhuma senha é commitada no `infra/redis/sentinel.conf` versionado.
- Healthcheck via `REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli -p 26379 ping | grep -q PONG`.

**Clientes Spring (gateway, authorization-server, user-service):**
- `spring.data.redis.password: ${REDIS_PASSWORD}` — autentica no data node.
- `spring.data.redis.sentinel.password: ${REDIS_PASSWORD}` — autentica nos sentinels
  (propriedade distinta, obrigatória separadamente no Spring Boot 4.0).
- `REDIS_PASSWORD` adicionado ao bloco `environment` de cada serviço no compose (fail-fast).

**redis-exporter:**
- `REDIS_PASSWORD: ${REDIS_PASSWORD}` — autentica nos 6 alvos (3 data + 3 sentinel) no
  modo multi-target (`/scrape?target=`). A imagem `oliver006/redis_exporter:v1.62.0` usa
  `REDIS_PASSWORD` como env var nativa para senha global (verificado empiricamente — esta
  var não segue o prefixo `REDIS_EXPORTER_` para o campo de senha).

**Fail-fast e `.env.example`:**
- Sintaxe `${REDIS_PASSWORD:?mensagem}` em todos os pontos de uso no compose → falha
  imediata com mensagem clara se a variável estiver ausente.
- `.env.example` documenta o bloco com instrução de geração segura (`openssl rand -hex 32`).

### Canal sob senha e o que permanece dependente de rede isolada

Com esta mudança, o canal Redis (data nodes e sentinels) fica **sob senha**:
- Leitura/escrita nos data nodes exige `AUTH <senha>`.
- Consulta aos sentinels (`SENTINEL get-master-addr-by-name`, `SENTINEL slaves`, etc.)
  exige `AUTH <senha>`.

O que ainda depende da invariante "portas nunca publicadas":
- Os data nodes e sentinels continuam sem TLS no transporte — a senha protege o protocolo
  de comando, não o canal. Um eavesdropper na rede interna Docker poderia capturar a senha
  no handshake `AUTH`. Mitigação prod completa = TLS no Redis (Redis 6+ com `tls-port`),
  fora do escopo desta tarefa.
- O exporter (`REDIS_PASSWORD`) autentica com a mesma senha; o endpoint
  `/scrape` do exporter em si não tem autenticação adicional (mesma situação anterior).

### Restrição operacional (ordem de deploy)

A ativação deve ser **atômica**: todos os 6 nós e todos os clientes Spring devem ser
atualizados na mesma janela de deploy. Uma atualização parcial (ex.: data nodes com senha,
clientes sem) causa `NOAUTH` e interrompe o serviço. Em ambientes com zero-downtime
exige-se uma janela de manutenção ou uso de ACLs com usuário sem senha de transição.

## Consequências

**Positivos:**
- Fecha o gap "Redis/Sentinel sem autenticação" de `docs/SECURITY.md`. Qualquer processo
  na rede interna que não possua a senha é rejeitado nos 6 nós.
- `requirepass` nos sentinels protege também a interface de gerenciamento do Sentinel
  (SENTINEL RESET, SENTINEL FAILOVER, etc.).

**Negativos / restrições:**
- Deploy requer janela atômica (todos os nós e clientes simultâneos).
- Healthchecks agora dependem de `REDIS_PASSWORD` estar corretamente disponível no env do
  container — falha de configuração é detectada na subida (fail-fast), não em runtime.
- O `sentinel.conf` versionado não tem a senha, mas os containers sentinels expõem a senha
  via `environment` no compose. Proteja o `.env` com permissões de arquivo adequadas.

**Serviços afetados:** gateway, authorization-server, user-service (clientes Spring);
redis-exporter (observabilidade); Prometheus (scrape multi-target via exporter — sem
mudança necessária no `prometheus.yml`).

## Alternativas consideradas

**Senha por tier (data ≠ sentinel):** senhas distintas para data nodes e sentinels.
Rejeitada: o `redis_exporter` em modo multi-target suporta apenas uma senha global
(`REDIS_PASSWORD`); múltiplas senhas exigiriam múltiplos exporters ou scraping
direto sem o exporter — aumenta a complexidade operacional sem ganho de segurança
significativo no contexto deste projeto.

**ACLs por usuário (Redis 6+):** criar usuários dedicados por serviço (gateway, auth-server,
user-service, exporter) com permissões mínimas. Rejeitada para esta tarefa: aumenta a
superfície de configuração (6 usuários × N serviços) e exigiria refatoração nos clientes
Spring (propriedade `username`). Dívida identificada: ACLs são o próximo passo natural
para prod.

**Múltiplos exporters por tier:** um exporter para data nodes, outro para sentinels.
Rejeitada pelos mesmos motivos da senha por tier — complexidade sem benefício proporcional.
