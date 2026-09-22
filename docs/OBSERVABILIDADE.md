# Observabilidade — tracing, métricas e dashboards

> Onde este documento se encaixa: [docs/LOGS.md](LOGS.md) cobre o log estruturado (formato, níveis,
> mascaramento de PII); [docs/CONFIG.md](CONFIG.md) cobre as variáveis de ambiente que ligam e
> desligam cada coisa. Aqui fica o **racional**: por que cada alvo, cada painel e cada threshold é
> como é — e, principalmente, o que **não** deve ser regredido ao mexer neles.
>
> Este conteúdo viveu no `CLAUDE.md` até 2026-08-10 e foi extraído porque era o único lugar onde
> existia: `docs/ARQUITETURA.md` chegava a apontar *de volta* para lá por falta de destino melhor.

---

## 1. Tracing distribuído (Zipkin)

Propagação **B3**, `management.tracing.propagation` em `b3` nos quatro apps Spring. O
`traceId`/`spanId` entra no MDC e aparece em todo log (ver `docs/LOGS.md`).

**Sampling.** Default de dev é **100%** (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY: 1.0` nos `*.yml`
do config-server). Em produção, reduza por env — `0.1` é um ponto de partida razoável.

> **Histórico que importa:** essa variável era documentada aqui e no `.env.example` mas era
> **inerte** — não havia `env_file` e ela não aparecia em `environment:` algum, então o deploy
> rodava a 100% independentemente do que estivesse no `.env`. Hoje é efetivamente repassada pelo
> anchor `x-spring-app-env` do compose. É um caso concreto da classe de defeito descrita em
> [docs/CONFIG.md](CONFIG.md) sobre variáveis declaradas e não repassadas: **variável documentada
> não é variável entregue** — confira o `environment:`/anchor antes de confiar nela.

**Storage.** Default `mem` (in-memory): os traces **somem no restart** do container Zipkin.
Parametrizável para Elasticsearch externo via `ZIPKIN_STORAGE_TYPE` / `ZIPKIN_ES_*`.

**Span órfão em chamada Feign.** Na cadeia Feign + circuit breaker, a instrumentação automática
(`feign-micrometer`) registra o span cliente no trace certo mas **não emite os headers B3** — o
serviço chamado abria um trace novo. Resolvido por `FeignTracingConfig` (duplicado de propósito em
`user-service` e `authorization-server`), que injeta o contexto corrente no `RequestTemplate` via o
`Propagator` do Micrometer.

---

## 2. Métricas (Prometheus)

Endpoint `/actuator/prometheus`, scrape a cada **5s**. Configuração em `infra/prometheus.yml`.

### Job `microservices`

- `discovery-server-1` / `discovery-server-2`: discovery estático nas portas `9091`/`9092`.
- `authorization-server`, `user-service`, `notification-service`, `gateway`: **`dns_sd_configs`** na
  **porta de management `8181`**, nunca na porta de tráfego.

Duas razões, ambas a preservar:

1. **A porta 8181 é o controle de acesso do actuator.** O actuator vive numa porta de management
   que o compose nunca publica — ver [docs/SECURITY.md](SECURITY.md) § Controles ativos. Apontar o
   scrape para a porta de tráfego reabriria a superfície.
2. **`dns_sd_configs` é o que dá um target por réplica** (ADR-024). Com `static_configs`, o nome do
   serviço resolvia para **uma** réplica por scrape e, a partir de N > 1, as séries alternavam entre
   processos a cada 5s — métricas que pareciam ruído e eram amostragem trocando de sujeito.

### Job `config-server`

`dns_sd_configs` sobre `config-server`, com **`basic_auth`**: o `/actuator/prometheus` desse serviço
fica atrás de HTTP Basic. A senha vem do Docker secret via
`password_file: /run/secrets/CONFIG_SERVER_PASSWORD` — **nunca inline** no YAML.

### Exporters de infraestrutura

Nenhum declara `ports:` na base do compose (prod-safe). Pins de imagem:

| Exporter | Porta | Imagem | Como coleta |
|---|---|---|---|
| `mongodb-exporter` | 9216 | `percona/mongodb_exporter:0.43.1` | Replica set via seed único `mongo-1:27017` + `replicaSet=rs0`; credencial por env (`MONGODB_URI`) |
| `postgres-exporter` | 9187 | `prometheuscommunity/postgres-exporter:v0.16.0` | `DATA_SOURCE_NAME` para `auth-postgres:5432/authdb` |
| `redis-exporter` | 9121 | `oliver006/redis_exporter:v1.62.0` | Multi-target (`/scrape`): 3 data nodes (`redis-1/2/3:6379`) + 3 sentinels (`redis-sentinel-1/2/3:26379`); relabel `__address__`→`instance` para não colidir |

Duas limitações conhecidas e aceitas: o `redis-exporter` é **SPOF de scrape** (um processo coleta os
seis alvos), e o seed único do `mongodb-exporter` cria dependência de `mongo-1` estar no ar.

---

## 3. Dashboards (Grafana)

Cinco dashboards pré-provisionados em `infra/grafana/dashboards/`, todos **revisados para a
elasticidade do ADR-024**. Os cinco **declaram `datasource: "Prometheus"` por painel** — a dívida do
`isDefault: true` está fechada; não volte a omitir o datasource.

### A regra que não pode ser regredida

> **Threshold que assume o topo da escala é bug de dashboard.**

O `dashboardRedis` ficava **vermelho permanente na topologia default** porque contava os 6 nós do
profile `ha`, e o piso mínimo tem 2. Hoje são dois contadores separados — data nodes e sentinels,
distinguidos pela porta no label `instance` — e ambos ficam verdes a partir de 1. Qualquer painel
novo precisa ser correto **no piso**, não só no topo: um alerta que só é verde na configuração
máxima treina o time a ignorar o vermelho.

### `dashboardHTTP.json` e `dashboardJVM.json`

Têm **duas** template vars: `application` e `instance` (multi-valor, `All` por default, encadeada em
`application`). O motivo é que com N réplicas o agregado responde *"o serviço está bem?"* e só a
quebra por réplica responde *"qual réplica está mal?"* — perguntas diferentes, ambas necessárias.

Três painéis existem **por causa da escala**:

- **"RPS por réplica"** (HTTP) — é a pergunta *"a carga está distribuída?"*. Medido em 11/10/9 de 30
  requisições via Eureka.
- **"Réplicas atendendo"** (HTTP) — distingue *registrada* de *usada*. Diverge de "Réplicas" no JVM
  exatamente quando o balanceamento falha, e essa divergência é o sinal.
- **"Heap usado por réplica vs teto"** (JVM) — o painel antigo comparava `sum(usado)` com
  `sum(máximo)`; como os dois lados triplicam com 3 réplicas, **uma réplica a 95% de heap sumia na
  média**.

**`p95`/`p99` agregam buckets antes do `histogram_quantile`.** Quebrar por réplica e tirar média de
quantis é matematicamente errado — há comentário no próprio arquivo dizendo isso. Não "corrija" para
parecer consistente com os outros painéis.

### `dashboardMongo.json`

Par **"Membros saudáveis" × "Membros configurados"**. A divergência entre os dois (ex.: 3
configurados / 1 saudável) é a **assinatura exata** do Mongo somente-leitura descrito no runbook de
encolhimento em [docs/CONFIG.md](CONFIG.md).

### `dashboardRedis.json`

Os dois `stat` de topo — **"Data nodes UP (piso 1 · ha 3)"** e **"Sentinels UP (piso 1 · ha 3)"** —
têm threshold **verde a partir de 1**, não de 3, de propósito: o piso mínimo do
[ADR-024](adr/ADR-024-elasticidade-piso-minimo-eixos-escala.md) roda com um nó e um Sentinel, e um
alerta em 3 pintaria de vermelho a topologia default. O título carrega os dois valores esperados
porque o painel sozinho não distingue "piso mínimo saudável" de "`ha` degradado" — quem distingue é
o operador que sabe com qual profile subiu.

Os painéis de **replicação** (`redis_connected_slaves`, `redis_master_link_up`) e de **Sentinel**
(`redis_sentinel_master_ok_sentinels` / `..._ok_slaves`) só têm série no profile `ha`; no piso
mínimo ficam legitimamente vazios, e isso **não** é falha de scrape.

> Lembrete de coleta: o `redis-exporter` é **SPOF de scrape** — um processo coleta os seis alvos.
> Se este dashboard zerar inteiro, suspeite do exporter antes de suspeitar do Redis.

### `dashboardPostgres.json`

**"Headroom de conexões"** e **"Conexões vs teto"** tiram de `docs/CONFIG.md` e põem em painel o
único limite de escala cuja violação **falha em produção**:

```
N réplicas × AUTH_DB_POOL_SIZE ≤ max_connections
```

---

## 4. SLOs

Buckets de latência publicados: **50ms · 100ms · 200ms · 500ms · 1s · 2s**.

---

## 5. Acesso e exposição

Grafana, Prometheus e Zipkin são publicados **presos ao loopback** (`127.0.0.1:PORTA:PORTA`), tanto
em dev (`docker-compose.override.yml`) quanto no deploy (`docker-compose.deploy.yml`), e **não têm
regra de ingress no túnel Cloudflare**.

Esse bind **é** o controle de acesso: nenhum dos três tem lockout, rate limit ou MFA, e Prometheus e
Zipkin não têm autenticação alguma. Republicar sem o IP (`- "3000:3000"`) devolve o acesso à LAN
inteira. Ver [docs/SECURITY.md](SECURITY.md).
