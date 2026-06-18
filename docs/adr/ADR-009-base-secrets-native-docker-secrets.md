# ADR-009: Base `docker-compose.yml` secrets-native (Docker secrets no lugar do `.env` plano)

- **Status:** aceita
- **Data:** 2026-06-17
- **Serviço alvo:** todos (config-server · discovery-server · authorization-server · user-service · gateway · infra: Mongo · Postgres · Redis/Sentinel · Grafana · Prometheus · exporters)
- **Tarefa relacionada:** RELATORIOA Tier 0 — gap 0.3 (segredos em arquivo `.env` plano)

## Contexto

Na v1 do blueprint, todos os segredos (`OAUTH_CLIENT_SECRET`, `MONGO_PASSWORD`,
`POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `INTERNAL_API_TOKEN`, `CONFIG_SERVER_PASSWORD`,
`GRAFANA_ADMIN_PASSWORD`) viviam em **texto plano no `.env`**, interpolados via `${VAR}` no
`docker-compose.yml`. Esse é o **gap 0.3 do RELATORIOA**: segredo em arquivo no host é superfície
de vazamento (backups do `.env`, `docker inspect`, logs de processo) e não tem rotação.

Restrições que moldaram a decisão:

- **Sem Swarm.** O alvo é Docker Compose puro (deploy na própria máquina). As chaves
  `uid`/`gid`/`mode` do *long-syntax* de secrets **só valem em Swarm** — em Compose não-Swarm os
  secrets `file:` são **bind-montados preservando o modo do host**.
- **`${VAR:?}` é parse-time.** A base usa fail-fast por variável; a env do SO **vence** o
  configtree. Migrar de forma limpa exige tornar a **própria base** secrets-native (um overlay
  aditivo não removeria as envs nem teria precedência).
- **Imagens heterogêneas.** Cada serviço tem um mecanismo distinto para ler segredo de arquivo;
  algumas imagens (distroless) não têm shell nem flag de arquivo.

## Decisão

Tornar o **`docker-compose.yml` base secrets-native**: os segredos saem do `.env` para arquivos
em `./secrets/` (gitignorado), declarados no bloco top-level `secrets:` e montados em
`/run/secrets/<NOME>`. Gerados **uma vez** por `infra/secrets/gen-secrets.sh` (defaults de DEV;
em prod, exportar cada variável com valor forte). **`docker compose up` falha sem `./secrets/`** —
`gen-secrets.sh` é pré-requisito de execução (documentado em `README.md` e `CLAUDE.md`).

Mecanismo de consumo por tipo de serviço:

| Consumidor | Mecanismo | Secret(s) |
| --- | --- | --- |
| Spring (config/discovery/auth/user/gateway) | `spring.config.import=configtree:/run/secrets/` (nome do arquivo = *placeholder* da property) | `CONFIG_SERVER_PASSWORD`, `OAUTH_CLIENT_SECRET`, `REDIS_PASSWORD`, `INTERNAL_API_TOKEN`, `MONGODB_URI` |
| authorization-server (JWK) | `JWK_PRIVATE_KEY=file:/run/secrets/jwk_private` / `JWK_PUBLIC_KEY=file:/run/secrets/jwk_public` | `jwk_private`, `jwk_public` |
| postgres / mongo | convenção nativa `*_FILE` (`POSTGRES_PASSWORD_FILE`, `MONGO_INITDB_ROOT_PASSWORD_FILE`) | `POSTGRES_PASSWORD`, `MONGO_PASSWORD` |
| redis / sentinel | `$(cat /run/secrets/REDIS_PASSWORD)` em runtime (`$$` no compose); healthcheck via `REDISCLI_AUTH` | `REDIS_PASSWORD` |
| redis-exporter | `--redis.password-file` (espera **JSON** `{target: senha}`, não a senha crua) | `redis_exporter_json` |
| postgres-exporter | `$(cat ...)` montando o `DATA_SOURCE_NAME` em runtime | `POSTGRES_PASSWORD` |
| grafana | `GF_SECURITY_ADMIN_PASSWORD__FILE` | `GRAFANA_ADMIN_PASSWORD` |
| prometheus | `basic_auth.password_file` (scrape do config-server) | `CONFIG_SERVER_PASSWORD` |

**Invariantes a preservar (quebrá-las regride bugs já resolvidos no 1º boot):**

- **`chmod 644`, não 600.** Os consumidores rodam como usuários **não-root** (`appuser` UID 999
  nos serviços Spring; `mongodb` após re-exec; grafana UID 472). Como o modo do host é preservado
  (não-Swarm), um `600` do dono UID-host fica ilegível. `gen-secrets.sh` usa 644. Tradeoff aceito:
  legível por outros usuários do host (`./secrets/` é gitignorado e o host é single-tenant).
- **Sem newline final.** `gen-secrets.sh` usa `printf '%s'` — um `\n` final entraria no valor lido
  pelo configtree/`cat` e quebraria a autenticação.
- **`redis_exporter_json` é JSON multi-alvo**, mapeando os 6 alvos (3 data nodes + 3 sentinels) à
  `REDIS_PASSWORD` — a flag `--redis.password-file` não aceita a senha crua.

## Consequências

**Positivas:**

- Segredo sai do `.env` plano (e dos backups do `.env`) — fecha **parcialmente** o gap 0.3.
- Caminho único e uniforme (`configtree`) para os serviços Spring; mecanismos nativos para o resto.
- `docker compose config -q` **não** exige os arquivos de secret → o job `compose-validate` do CI
  segue verde sem eles; só `up` exige.

**Negativas / atenção:**

- **Fechamento parcial.** Em Compose sem Swarm os secrets continuam **arquivos no host** — não há
  secret manager gerenciado nem rotação. Para "deploy legítimo" real, evoluir para Vault / AWS/GCP
  Secret Manager (registrado em `docs/SECURITY.md`).
- **Resíduo consciente:** o `mongodb-exporter` (imagem distroless, sem shell nem flag de arquivo)
  continua lendo `MONGO_USER`/`MONGO_PASSWORD` do `.env` — deve casar com `./secrets/MONGO_PASSWORD`.
  Único segredo fora do Docker secrets. Registrado em `docs/SECURITY.md`.
- Novo **pré-requisito de execução** (`gen-secrets.sh`): sem ele o `up` falha.

Sem mudança de contrato de API. Relaciona-se a [[ADR-005]] (a chave JWK passa a ser secret) e
[[ADR-008]] (a `REDIS_PASSWORD` antes em env, agora secret).

## Alternativas consideradas

- **Overlay aditivo (`docker-compose.secrets.yml`).** Descartada: a env do SO definida na base
  (`${VAR:?}`, parse-time) **vence** o configtree, e um overlay não remove a env nem teria
  precedência → migração só fica limpa tornando a **base** secrets-native.
- **Long-syntax com `uid/gid/mode`.** Descartada: essas chaves **só** têm efeito em Swarm; em
  Compose puro são ignoradas (o modo vem do host) — daí a opção pelo `chmod 644` no gerador.
- **Secret manager gerenciado (Vault / AWS / GCP) já agora.** Fora de escopo desta onda (deploy na
  própria máquina); é o passo seguinte para cruzar a barra do 0.3 — mantido como dívida nomeada.
