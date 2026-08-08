# Variáveis de Ambiente

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler esta referência](#como-ler-esta-referência)
- [Docker secrets (base secrets-native)](#docker-secrets-base-secrets-native)
- [Infraestrutura e descoberta](#infraestrutura-e-descoberta)
- [Dados (Mongo · Postgres · Redis)](#dados-mongo--postgres--redis)
- [OAuth2 e JWT](#oauth2-e-jwt)
- [Canal interno](#canal-interno)
- [Consentimento LGPD](#consentimento-lgpd)
- [Verificação de e-mail (ADR-015)](#verificação-de-e-mail-adr-015)
- [Swagger / OpenAPI](#swagger--openapi)
- [CORS](#cors)
- [Deploy público (Cloudflare Tunnel)](#deploy-público-cloudflare-tunnel--docker-composedeployyml)
- [Borda e IP do cliente (lockout / rate limit)](#borda-e-ip-do-cliente-lockout--rate-limit)
- [Observabilidade](#observabilidade)
- [Front-end](#front-end)
- [Containers de infraestrutura (só `docker-compose`)](#containers-de-infraestrutura-só-docker-compose)
- [Limites de recursos (CPU / memória)](#limites-de-recursos-cpu--memória)

## Como ler esta referência

- **Default dev** é o valor embutido (no `*.yml` do config-server ou em `@Value` no código) quando a variável não é exportada.
- Variáveis **sem default** são _fail-fast_: o app não sobe sem elas (o `docker-compose.yml` injeta um valor de dev).
- A coluna **Serviço(s)** indica quem consome a variável.
- **Compose base prod-safe + override de dev:** `docker-compose.yml` **não publica porta nenhuma** no host — nem `gateway`, nem `interface` (G10/ADR-019; a asserção (e) do smoke-test de login verifica isso). Quem publica é o `docker-compose.override.yml` (auto-carregado por `docker compose up`, portas internas em dev) e o `docker-compose.deploy.yml` (só Grafana/Prometheus/Zipkin, presos a `127.0.0.1`; a borda pública chega pelo túnel). As URLs voltadas ao **browser** (front-channel/redirects) entram no compose como `${VAR:-localhost-default}` — em dev usam o default; em **prod** (`docker compose -f docker-compose.yml up`) um `.env` as sobrescreve com os hostnames públicos. Ver `.env.example`.
- **Secrets sem default no config-server:** `AUTH_DB_USER`, `AUTH_DB_PASSWORD` e `OAUTH_CLIENT_SECRET` deixaram de ter default nos YAMLs servidos → ausência da env no cliente derruba a subida (mesma filosofia do `INTERNAL_API_TOKEN`).

> **Uma variável só é ajustável se estiver declarada no compose.** Ter default no YAML do
> config-server **não** basta: o `.env` só alimenta interpolação `${VAR}` do próprio compose, e não
> há `env_file` em serviço nenhum. Uma variável fora de todo bloco `environment:` (e que também não
> chegue como Docker secret via `configtree:`) é **inerte** — documentá-la aqui promete um knob que
> não existe. Em 2026-08-06, uma varredura de todo `${VAR}` lido pelos YAMLs contra os três compose
> e o `./secrets/` encontrou **19 variáveis nesse estado**, entre elas `TERMS_VERSION`,
> `TRUSTED_CLIENT_IP_HEADER` e `LOCKOUT_MAX_ATTEMPTS` — todas com linha nesta referência e nenhuma
> ajustável. Foram ligadas em bloco.
>
> O padrão adotado é **espelhar o default do YAML no compose** (`VAR: ${VAR:-default-do-yaml}`),
> como em `JAVA_TOOL_OPTIONS` e `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`. Duas regras:
> **(1)** nunca escrever `${VAR:-}` — o default vazio injeta string vazia e **sobrescreve** o
> default do YAML em vez de deixá-lo valer; **(2)** o preço é o default existir em dois lugares —
> ao mudar um, mudar o outro. Ao acrescentar um knob novo, ligá-lo no compose **no mesmo commit**,
> senão ele nasce inerte. Verificação: `docker compose config | grep NOME_DA_VAR`.
- **Segredos vêm de Docker secrets, não do `.env`:** os valores sensíveis são injetados por arquivos em `/run/secrets/` (base secrets-native) — ver [§ Docker secrets](#docker-secrets-base-secrets-native). Onde as tabelas abaixo dizem "o compose injeta `<valor-dev>`", o valor vem do secret correspondente gerado por `infra/secrets/gen-secrets.sh`.
- **Config-server com Basic auth:** o endpoint do config-server exige autenticação (só `/actuator/health` fica aberto, para os healthchecks). O par `CONFIG_SERVER_USERNAME`/`CONFIG_SERVER_PASSWORD` vale para o usuário in-memory do config-server (`spring.security.user.*`) **e** para os clientes (`spring.cloud.config.username`/`password`).

## Docker secrets (base secrets-native)

A base `docker-compose.yml` é **secrets-native** (gap 0.3 RELATORIOA, [ADR-009](adr/ADR-009-base-secrets-native-docker-secrets.md)): os segredos **não** ficam no `.env`, mas em arquivos sob `./secrets/` (gitignorado), montados em `/run/secrets/<NOME>`. Gere uma vez antes do `docker compose up` (o `up` **falha** sem eles):

```bash
infra/secrets/gen-secrets.sh        # defaults de DEV
REDIS_PASSWORD=$(openssl rand -hex 32) OAUTH_CLIENT_SECRET=... infra/secrets/gen-secrets.sh   # prod
```

> **Ao adicionar um secret novo à tabela abaixo** (o último foi `SMTP_SSL_ENABLE`): declarar o
> arquivo no compose o torna **obrigatório** — o `up` falha se ele não existir em `./secrets/`,
> e **o CI não pega**, porque o job `compose-validate` roda `docker compose config -q`, que
> valida a topologia mas **não** checa existência de arquivo de secret (é justamente o que
> permite o CI passar hoje sem `./secrets/`). O erro só aparece na subida.
>
> **Não re-rode o `gen-secrets.sh` para acrescentar um secret a um deploy existente.** O script
> não reconcilia nada: sobrescreve `./secrets/` inteiro e, além de trocar por defaults de dev toda
> senha não exportada, **regera o par JWK** (o `gen-keys.sh` roda `openssl genpkey` sempre). A
> chave de assinatura muda, todo access/refresh token em circulação deixa de ser verificável e
> todos os usuários logados caem. É por isso que o roteiro do [README § 2b](../README.md) termina
> em `down -v` + `up` — ele descarta o estado junto. Para só acrescentar o arquivo que falta:
>
> ```bash
> printf '%s' false > secrets/SMTP_SSL_ENABLE && chmod 644 secrets/SMTP_SSL_ENABLE
> ```

| Arquivo em `./secrets/` | Consumidor(es) | Mecanismo de leitura |
| --- | --- | --- |
| `CONFIG_SERVER_PASSWORD` | serviços Spring · prometheus | `configtree:/run/secrets/` · `basic_auth.password_file` |
| `OAUTH_CLIENT_SECRET` | gateway · auth-server | `configtree:/run/secrets/` |
| `INTERNAL_API_TOKEN` | user-service · auth-server · notification-service | `configtree:/run/secrets/` |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` / `SMTP_AUTH` / `SMTP_STARTTLS` / `SMTP_SSL_ENABLE` | notification-service | `configtree:/run/secrets/` |
| `REDIS_PASSWORD` | redis-1/2/3 · sentinels · serviços Spring | `$(cat ...)` (runtime) · `configtree:/run/secrets/` |
| `redis_exporter_json` | redis-exporter | `--redis.password-file` (JSON `{target: senha}`, não a senha crua) |
| `POSTGRES_PASSWORD` | postgres · postgres-exporter | `POSTGRES_PASSWORD_FILE` (nativo) · `$(cat ...)` |
| `MONGO_PASSWORD` | mongo | `MONGO_INITDB_ROOT_PASSWORD_FILE` (nativo) |
| `MONGODB_URI` | user-service | `configtree:/run/secrets/` |
| `jwk_private` / `jwk_public` | authorization-server | `JWK_PRIVATE_KEY=file:/run/secrets/jwk_private` (gerado por `infra/jwk/gen-keys.sh`) |
| `GRAFANA_ADMIN_PASSWORD` | grafana | `GF_SECURITY_ADMIN_PASSWORD__FILE` |
| `CLOUDFLARE_TUNNEL_CREDENTIALS` | cloudflared (só no overlay `docker-compose.deploy.yml`) | `credentials-file:` dentro de `infra/cloudflared/config.yml` |

> **`CLOUDFLARE_TUNNEL_CREDENTIALS` é o único secret sem default de dev, e o único que é *copiado* em vez de gerado.** É o JSON emitido por `cloudflared tunnel create` (contém `AccountTag`, `TunnelID` e `TunnelSecret`), que fica em `~/.cloudflared/<UUID>.json`; aponte a variável para esse caminho e o `gen-secrets.sh` copia o arquivo. Se a variável não vier do ambiente, o script grava um arquivo **vazio** e o `cloudflared` recusa a subida — comportamento desejado (melhor que um túnel que não roteia nada); se vier apontando para um caminho inexistente, o script **falha na hora**. A base e o dev ignoram esse arquivo. O segredo é referenciado de dentro do `config.yml` (e não por flag) porque a imagem do `cloudflared` é distroless — sem shell, `$(cat ...)` não funcionaria — e o arquivo o mantém fora da listagem de env do container.

> **No `configtree`, o nome do arquivo é o *placeholder* da property** (ex.: `OAUTH_CLIENT_SECRET` → `${OAUTH_CLIENT_SECRET}`). Arquivos gerados com `printf '%s'` (sem newline final) e `chmod 644` (consumidores rodam não-root; em Compose não-Swarm o modo do host é preservado) — ver ADR-009.
>
> **Resíduo 0.3:** o `mongodb-exporter` (imagem distroless) **não** lê secret — continua usando `MONGO_USER`/`MONGO_PASSWORD` do `.env`, que devem casar com `./secrets/MONGO_PASSWORD` (ver [SECURITY.md](SECURITY.md)).

## Infraestrutura e descoberta

| Variável            | Serviço(s)             | Default dev                     | Observação                                                            |
| ------------------- | ---------------------- | ------------------------------- | -------------------------------------------------------------------- |
| `SERVER_PORT`       | todos                  | por serviço¹                    | Porta HTTP do serviço.                                                |
| `CONFIG_SERVER_URL` | todos (exceto config)  | —                               | `spring.config.import=optional:configserver:...`. Compose: `http://config-lb:8888` (nginx na frente do serviço `config-server`, resolvido por DNS — `--scale config-server=N` entra em rotação em ≤10s, sem editar arquivo; ADR-024). |
| `CONFIG_SERVER_USERNAME` | config-server · clientes | `config-client`             | Usuário do Basic auth do config-server. No config-server vira `spring.security.user.name`; nos clientes, `spring.cloud.config.username`. |
| `CONFIG_SERVER_PASSWORD` | config-server · clientes | `config-dev-secret`         | Senha do Basic auth. **É Docker secret, não variável de ambiente** — montada em `/run/secrets/CONFIG_SERVER_PASSWORD` e resolvida pelo configtree; o `prometheus.yml` a lê por `password_file`. Por isso **não** está no `.env.example` (que lista só `CONFIG_SERVER_USERNAME`). Quem o compose passa sem `:-default` é o **`CONFIG_SERVER_USERNAME`**: se faltar no `.env`, o container recebe a var vazia (não cai no default do `application.yml`). |
| `EUREKA_URI`        | todos (exceto discovery) | `http://localhost:9091/eureka` | `defaultZone` do Eureka. Compose: lista CSV com ambas as instâncias HA (`http://discovery-server-1:9091/eureka,http://discovery-server-2:9092/eureka`). |
| `EUREKA_PEER_URL`   | discovery-server       | `http://localhost:9091/eureka`  | URL do **peer** Eureka (a outra instância). Cada nó aponta para o outro. |
| `EUREKA_HOSTNAME`   | discovery-server       | `localhost`                     | Hostname que a instância anuncia ao peer. Compose: `discovery-server-1` / `discovery-server-2`. |

> ¹ Defaults de porta: gateway `8081`, authorization-server `8082`, user-service `8090`, **notification-service `8095`**, discovery-server `9091`/`9092`, config-server `8888`.
>
> **Porta de management:** os **quatro** serviços de domínio (gateway, user-service, authorization-server, notification-service) servem `/actuator/**` em `management.server.port: 8181` — a mesma porta nos quatro, o que não colide porque cada container tem IP próprio na rede Docker. É **hardcoded** em cada YAML de propósito (como env, um `.env` errado devolveria o actuator à porta pública em silêncio) e **não é publicada** em nenhum compose: o Prometheus raspa `<serviço>:8181` pela rede interna e os healthchecks do compose usam `localhost:8181`. Fecha o gap **G14** (ver [SECURITY.md](SECURITY.md)) — não publique essa porta. Exceções: os **discovery-servers** servem o actuator na porta principal (`9091`/`9092`), e o **config-server** o mantém na `8888` atrás de HTTP Basic, com só `/actuator/health` aberto.

### Variáveis de plataforma (fáceis de esquecer)

| Variável | Serviço(s) | Default | Observação |
| --- | --- | --- | --- |
| `SPRING_CONFIG_IMPORT` | todos os Spring | — | **É o que liga todo o mecanismo de segredos.** O compose a injeta (âncora `x-spring-config`) com `configtree:/run/secrets/` + `optional:configserver:${CONFIG_SERVER_URL}`. Removê-la desativa **todos** os Docker secrets de uma vez, silenciosamente. |
| `APP_COOKIE_SECURE` | gateway · authorization-server | `false` | Liga a flag `Secure` nos cookies de sessão (`SESSION` e `AUTHSESSION`). Fica `false` em dev (HTTP) e `true` no overlay de deploy — a simetria entre os dois serviços é invariante do [ADR-007](adr/ADR-007-sessao-redis-cookies-distintos.md). |
| `SPRING_PROFILES_ACTIVE` | config-server | `native` | Perfil do config-server (serve de `classpath:/config`). |
| `JWK_RSA_BITS` | `infra/jwk/gen-keys.sh` | `2048` | Tamanho da chave RSA gerada pelo script (não é lida por serviço algum). |
| `SECRETS_DIR` | `infra/secrets/gen-secrets.sh` | `./secrets` | Diretório de saída dos segredos gerados. |

## Dados (Mongo · Postgres · Redis)

| Variável            | Serviço(s)                          | Default dev                                  | Observação                                                       |
| ------------------- | ----------------------------------- | -------------------------------------------- | ---------------------------------------------------------------- |
| `MONGODB_URI`           | user-service                        | — (obrigatória)      | **Docker secret**, montado em `/run/secrets/MONGODB_URI` e resolvido pelo configtree — **não** é montado pelo compose como env. Gerado pelo `infra/secrets/gen-secrets.sh`, que interpola `${MONGO_USER}` (template: `root`) e a senha: `mongodb://<MONGO_USER>:<MONGO_PASSWORD>@mongo-1:27017,mongo-2:27017,mongo-3:27017/user-db?replicaSet=rs0&authSource=admin`. |
| `MONGODB_DATABASE`      | user-service                        | `user-db`            | Base de dados de usuários.                                                          |
| `REDIS_SENTINEL_MASTER` | user-service · gateway · auth-server | `mymaster`          | Nome do master monitorado pelos Sentinels. Substitui `REDIS_HOST`/`REDIS_PORT`.     |
| `REDIS_SENTINEL_NODES`  | user-service · gateway · auth-server | `redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379` | Lista CSV de endereços dos processos Sentinel. |
| `REDIS_PASSWORD`        | redis-1/2/3 · redis-sentinel-1/2/3 · user-service · gateway · auth-server · redis-exporter | — (fail-fast) | Senha uniforme dos 6 nós Redis (ADR-008). Data nodes: `--requirepass`/`--masterauth`; sentinels: injetada em runtime no `/tmp/sentinel.conf` (`requirepass` + `sentinel auth-pass`). Clientes Spring: `spring.data.redis.password` (data nodes) e `spring.data.redis.sentinel.password` (sentinels). `redis-exporter`: JSON multi-alvo no secret `redis_exporter_json`. **Docker secret** (`/run/secrets/REDIS_PASSWORD`), não env — data nodes/sentinels via `$(cat ...)`, clientes Spring via `configtree`. Gere com `openssl rand -hex 32` (ver [§ Docker secrets](#docker-secrets-base-secrets-native)). |
| `SESSION_TIMEOUT`       | gateway · auth-server               | `30m`                                        | TTL de inatividade da sessão em Redis (Spring Session). Default explícito 30m; aceita unidades de duração (`30m`, `2h`). |
| `AUTH_DB_URL`       | authorization-server                | `jdbc:postgresql://localhost:5432/authdb`    | Datasource do Postgres que guarda o estado OAuth.                |
| `AUTH_DB_USER`      | authorization-server                | — (fail-fast)                                | Sem default no config-server; o compose injeta `${POSTGRES_USER}`. |
| `AUTH_DB_PASSWORD`  | authorization-server                | — (fail-fast)                                | Sem default no config-server; o compose injeta `${POSTGRES_PASSWORD}`. |

## OAuth2 e JWT

| Variável                   | Serviço(s)            | Default dev                                          | Observação                                                                                  |
| -------------------------- | --------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `AUTH_ISSUER_URI`          | user-service · gateway | `http://localhost:8082`                              | `issuer-uri` para validação do JWT (resource server). Hostname **interno** (back-channel).  |
| `AUTH_ISSUER`              | authorization-server  | `http://localhost:8082`                              | Issuer que o próprio auth-server anuncia. Externalizável, mas o valor **interno** (`http://authorization-server:8082` no compose) é o correto mesmo sob domínio público: tem de casar com o `AUTH_ISSUER_URI` dos resource servers, que validam o JWT pela rede Docker. |
| `JWK_PRIVATE_KEY`          | authorization-server  | `classpath:keys/app.key`                             | Chave privada RSA (PEM). A chave **não é mais versionada** (gap 0.1, [ADR-005](adr/ADR-005-chave-jwk-persistente.md)): gerada fora do repo por `infra/jwk/gen-keys.sh`. No compose secrets-native = `file:/run/secrets/jwk_private` (secret `jwk_private`). Em dev manual, `gen-keys.sh` escreve no classpath. |
| `JWK_PUBLIC_KEY`           | authorization-server  | `classpath:keys/app.pub`                             | Chave pública RSA (PEM). Idem — secret `jwk_public` no compose (`file:/run/secrets/jwk_public`). |
| `JWK_KEY_ID`               | authorization-server  | `user-service-key`                                   | `kid` estável da chave de assinatura.                                                        |
| `OAUTH_CLIENT_ID`          | gateway · auth-server | `gateway-client`                                     | Client confidencial do BFF.                                                                  |
| `OAUTH_CLIENT_SECRET`      | gateway · auth-server | — (fail-fast)                                        | Sem default no config-server; injetado via Docker secret (`configtree:/run/secrets/`). Sobrescrever em produção. |
| `OAUTH_REDIRECT_URI`       | gateway               | `{baseUrl}/login/oauth2/code/gateway-client`         | `redirect-uri` do fluxo. Em **dev manual** (`npm run dev`), exporte `http://localhost:5173/login/oauth2/code/gateway-client`.² |
| `OAUTH_AUTHORIZATION_URI`  | gateway               | `http://localhost:8082/oauth2/authorize`             | Endpoint de autorização **front-channel** (browser). Só este endpoint usa hostname externo. |
| `OAUTH_END_SESSION_URI`    | gateway               | `http://localhost:8082/connect/logout`               | Endpoint de logout OIDC do IdP (browser). Lido em `gateway/.../SecurityConfig.java`.        |
| `POST_LOGOUT_REDIRECT_URI` | gateway               | `http://localhost:5173/`                             | Para onde o IdP devolve o browser após o logout. Deve bater com a `postLogoutRedirectUri` registrada no `RegisteredClient`. |
| `OAUTH_CLIENT_REDIRECT_URIS` | authorization-server | `http://localhost:8081/login/oauth2/code/gateway-client,http://localhost:5173/login/oauth2/code/gateway-client,http://localhost:8081/swagger-ui/oauth2-redirect.html,https://oauth.pstmn.io/v1/callback` | CSV dos `redirectUri` do `gateway-client` semeado em `OAuth2ClientConfig` (`oauth.gateway-client.redirect-uris`). **Mudar em prod exige re-seed:** o seed do `RegisteredClient` é idempotente só no `findByClientId` inicial — não há reconciliação; alterar a env sem recriar/atualizar o client no Postgres não atualiza os redirect URIs já persistidos. |
| `OAUTH_CLIENT_POST_LOGOUT_URIS` | authorization-server | `http://localhost:5173/` | CSV dos `postLogoutRedirectUri` do `gateway-client` (`oauth.gateway-client.post-logout-uris`). Mesma ressalva de re-seed acima. |

> ² O `RegisteredClient` no authorization-server já permite a URI de `:5173`, para o callback aterrissar no SPA.

## Canal interno

| Variável             | Serviço(s)              | Default dev      | Observação                                                                                       |
| -------------------- | ----------------------- | ---------------- | ------------------------------------------------------------------------------------------------ |
| `INTERNAL_API_TOKEN` | user-service · auth-server · notification-service | — (fail-fast) | Shared secret do canal `/internal/**` (header `X-Internal-Token`). Sem default no config-server — o app **não sobe** sem ele; o compose injeta `internal-dev-token`. **Mesmo valor nos três serviços** (notification-service reaproveita o secret já existente, sem token dedicado — ADR-015). |

## Consentimento LGPD

| Variável         | Serviço(s)   | Default dev | Observação                                                                                                  |
| ---------------- | ------------ | ----------- | ----------------------------------------------------------------------------------------------------------- |
| `TERMS_VERSION`  | user-service | `v1`        | Versão dos termos/privacidade registrada no cadastro (`app.terms.version`, ADR-012). **Bump** quando a política mudar — permite exigir re-consentimento e prova qual versão cada titular aceitou. |
| `AUDIT_LOG_RETENTION` | user-service | `180d` | Retenção da trilha de auditoria LGPD (`app.audit.retention`, ADR-022). Gravada por documento em `auditLogs.purgeAt`, com índice TTL *expire-at* — mudar o valor vale para as entradas novas, sem `collMod`. Entradas gravadas antes da introdução do campo não têm `purgeAt` e nunca expiram. **Decisão de conformidade, não técnica:** se o prazo legal aplicável for maior, ajuste **antes** que a primeira entrada expire — depois disso o dado não volta. |

## Higiene do estado persistente (ADR-022)

| Variável                       | Serviço(s)           | Default dev | Observação                                                                                                  |
| ------------------------------- | -------------------- | ----------- | ------------------------------------------------------------------------------------------------------------ |
| `OAUTH_STATE_PURGE_INTERVAL`    | authorization-server | `6h`        | Período da purga de `oauth2_authorization` (`app.oauth-state.purge-interval`). O SAS grava uma linha por autorização e **nunca** apaga nenhuma — sem esta varredura a tabela cresce uma linha por login, para sempre. O lock entre instâncias (`SETNX` no Redis) usa TTL de 2× este valor. |
| `OAUTH_STATE_PURGE_GRACE`       | authorization-server | `1d`        | Folga além da expiração antes de apagar (`app.oauth-state.purge-grace`) — margem para depuração/forense. Uma linha só é elegível quando **todas** as suas colunas de expiração já passaram por esta margem; filtrar só por `access_token_expires_at` apagaria autorizações com refresh token vivo, deslogando usuários ativos. |
| `OAUTH_STATE_PURGE_BATCH_SIZE`  | authorization-server | `500`       | Teto de linhas removidas por ciclo (`app.oauth-state.purge-batch-size`). Limita o tamanho da transação: numa base que acumulou meses de estado, um `DELETE` único seguraria lock sobre a tabela inteira. O ciclo seguinte continua de onde parou. |

## Verificação de e-mail (ADR-015)

| Variável                              | Serviço(s)            | Default dev              | Observação                                                                                                  |
| -------------------------------------- | ---------------------- | ------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `EMAIL_VERIFICATION_TOKEN_TTL`         | user-service           | `15m`                     | TTL do token opaco de verificação (`app.verification.token-ttl`).                                            |
| `EMAIL_VERIFICATION_RESEND_MAX`        | user-service           | `3`                       | Limite de reenvios por conta-alvo na janela (`app.verification.resend-max-per-window`, Redis, complementar ao rate limit por IP do gateway). |
| `EMAIL_VERIFICATION_RESEND_WINDOW`     | user-service           | `1h`                      | Janela do limite acima (`app.verification.resend-window`).                                                   |
| `EMAIL_VERIFICATION_OUTBOX_RETENTION`  | user-service           | `30d`                     | Retenção do registro de outbox (`notificationOutbox.purgeAt`) após expiração/confirmação do token — TTL index do Mongo atua sobre `purgeAt`, não sobre `expiresAt` (preserva histórico de auditoria). |
| `EMAIL_VERIFICATION_RETRY_INTERVAL`    | user-service           | `5m`                      | Período da varredura do `OutboxRetryService` (`app.verification.retry-interval`, emenda do ADR-015). Reprocessa registros `FAILED`/`PENDING`. O lock entre instâncias (`SETNX` no Redis) usa TTL de 2× este valor. |
| `EMAIL_VERIFICATION_RETRY_BACKOFF`     | user-service           | `15m`                     | Idade mínima do registro antes de ser reprocessado (`app.verification.retry-backoff`). Vale também para o `PENDING`, que dentro desta janela ainda pode estar em voo no executor assíncrono — reemitir antes duplicaria um e-mail que estava para dar certo. |
| `EMAIL_VERIFICATION_RETRY_MAX_ATTEMPTS`| user-service           | `5`                       | Teto de retentativas por titular (`app.verification.retry-max-attempts`). Contado sobre o **número de registros** do par (titular, tipo), **não** sobre o campo `attempts`: cada retry cria um registro novo com `attempts=0`, então um contador por registro nunca expiraria e a varredura reemitiria para sempre. Reenvios manuais consomem o mesmo orçamento. |
| `EMAIL_VERIFICATION_GRACE_PERIOD`      | authorization-server   | `24h`                     | Janela de carência do gate de login (`security.email-verification.grace-period`) — login permitido mesmo com `emailVerified=false` dentro da janela desde `registrationDate`, evitando conta permanentemente inacessível se o e-mail nunca chegar. |
| `API_BASE_URL`                         | user-service           | `http://localhost:8081`  | Reaproveitada como base do link de confirmação (`app.verification.base-url`) — já documentada em [§ Swagger/OpenAPI](#swagger--openapi); deve apontar ao gateway (borda), não ao user-service interno. |
| `SERVER_PORT` (notification-service)   | notification-service   | `8095`                    | Porta do serviço — nunca publicada pelo gateway nem pelo compose base; só republicada em dev via `docker-compose.override.yml`. |
| `SMTP_HOST` / `SMTP_PORT`              | notification-service   | `localhost` / `1025`      | Host/porta do servidor SMTP (`spring.mail.host/port`). Defaults de dev compatíveis com um MailHog/Mailpit local — sem credenciais reais. |
| `SMTP_USERNAME` / `SMTP_PASSWORD`      | notification-service   | _(vazio)_                 | Credenciais SMTP — Docker secret, sem valor real por default (placeholder de dev). Exporte com credenciais reais antes de `gen-secrets.sh` em produção. |
| `SMTP_AUTH` / `SMTP_STARTTLS`          | notification-service   | `false` / `false`         | Docker secret (mesmo mecanismo de `SMTP_HOST` etc.) para `spring.mail.properties.mail.smtp.auth`/`starttls.enable`. Provedor na porta **587** exige ambos `true` — exporte antes de `gen-secrets.sh`. |
| `SMTP_SSL_ENABLE`                      | notification-service   | `false`                   | Docker secret para `spring.mail.properties.mail.smtp.ssl.enable` — TLS **implícito** (porta **465**). **Mutuamente exclusivo com `SMTP_STARTTLS`**: são duas topologias de conexão, não dois níveis de rigor. `587` → `SMTP_STARTTLS=true` + `SMTP_SSL_ENABLE=false`; `465` → `SMTP_STARTTLS=false` + `SMTP_SSL_ENABLE=true`. Sem este flag a porta 465 é inutilizável (socket em texto plano contra porta que espera handshake TLS imediato). Não há validação em código — ligar os dois é incoerente. |
| `SMTP_CONNECTION_TIMEOUT` / `SMTP_READ_TIMEOUT` / `SMTP_WRITE_TIMEOUT` | notification-service | `10000` (ms cada)         | **Não são Docker secrets** (não são segredo) — chegam pelo `environment:` do notification-service, com o default espelhado do config-server. Mapeiam `mail.smtp.connectiontimeout`/`timeout`/`writetimeout`. Existem porque o default do Jakarta Mail é **infinito** e o `NotificationController` bloqueia até `JavaMailSender.send()` retornar: sem eles um SMTP travado segura a thread do Tomcat para sempre. O `timelimiter` de 10s do Feign **não** cobre este lado (libera o chamador, que já estava livre — a chamada é `@Async`). `10000` e não menos porque o handshake TLS + AUTH contra SMTP real passa de 3s. |
| `APP_MAIL_FROM`                        | notification-service   | `no-reply@users.local`    | Remetente exibido nos e-mails de verificação (`app.mail.from`).                                              |

## Swagger / OpenAPI

| Variável                     | Serviço(s)             | Default dev                                              | Observação                                            |
| ---------------------------- | ---------------------- | ------------------------------------------------------- | ----------------------------------------------------- |
| `AUTH_URL`                   | gateway · user-service | `http://localhost:8082/oauth2/authorize`                | Authorize URL exibida no Swagger (`OpenAPIConfig`).   |
| `AUTH_TOKEN`                 | gateway · user-service | `http://localhost:8082/oauth2/token`                    | Token URL exibida no Swagger (`OpenAPIConfig`).       |
| `API_BASE_URL`               | user-service           | `http://localhost:8081`                                 | Base URL anunciada no OpenAPI do user-service.        |

> ³ **`OAUTH2SWAGGER_REDIRECT_URL` foi REMOVIDA** (2026-08-04, [ADR-020](adr/ADR-020-swagger-atras-da-sessao.md)): alimentava `springdoc.swagger-ui.oauth2-redirect-url`, que saiu junto com todo o bloco `springdoc.swagger-ui.oauth` do `gateway.yml` — o `oauth.client-secret` dali era materializado pelo springdoc como um `ui.initOAuth({...})` literal dentro de `/swagger-ui/swagger-initializer.js` e **publicava o client secret** para qualquer visitante. Nenhum serviço lê mais essa env; ela saiu do `docker-compose.yml` e do `docker-compose.deploy.yml`. Junta-se a `OAUTH_GATEWAY_CLIENT` e `OAUTH_SWAGGER_REDIRECT_URL`, removidas antes pelo mesmo critério (env que serviço nenhum lê). **Não reintroduzir.**
>
> **Deploy via Cloudflare Tunnel (`docker-compose.deploy.yml`):** todas as envs de borda derivam de `${PUBLIC_ORIGIN}`. `AUTH_URL`/`AUTH_TOKEN` **permanecem** e alimentam só o securityScheme do doc OpenAPI (`OpenAPIConfig`) — quem os usa é o browser na página do Swagger, não o back-channel do BFF. O overlay os define nos **dois** serviços que os leem (gateway **e** user-service), mais `API_BASE_URL` no user-service (sem ele o `servers[]` cai em `localhost:8081` e o "Try it out" dispara mixed content a partir da página HTTPS). O botão **Authorize** deixou de completar o fluxo (ADR-020) — sob o BFF o "Try it out" autentica pelo cookie `SESSION` e o `tokenRelay()` da rota, sem token no browser.
>
> Para o Swagger existir na origem pública, o `login-interface/nginx.conf` precisa dos `location /swagger-ui` e `/v3/api-docs` (sob hostname único o gateway não é alcançável direto). Ambos ficam atrás do **Cloudflare Access**.

## CORS

CORS na borda + configurável por ambiente. Cada serviço lê a property `cors.allowed-origins` da sua config servida, com `setAllowedOriginPatterns` (compatível com `allowCredentials`). O **user-service não tem CORS** (nunca recebe fetch cross-origin — só via gateway).

| Variável                    | Serviço(s)           | Default dev              | Observação                                                                                                   |
| --------------------------- | -------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------ |
| `CORS_ALLOWED_ORIGINS`      | gateway              | `http://localhost:5173`  | Origens (CSV) do **SPA** permitidas pela borda. Em prod, a origem pública real do SPA. Logada no startup do gateway. |
| `CORS_ALLOWED_ORIGINS_AUTH` | authorization-server | `http://localhost:8081`  | Origem (CSV) do **Swagger-UI** permitida no auth-server — o Swagger é cliente OAuth2 no browser e faz fetch cross-origin a `/oauth2/token`. Em prod, a origem pública do Swagger/borda. Compose usa `${VAR:-default}`. |

## Deploy público (Cloudflare Tunnel — `docker-compose.deploy.yml`)

O overlay de deploy é dirigido por **uma única variável**: a origem pública. Sob a topologia de
**hostname único** (o túnel entrega em `interface:80`, o nginx do SPA, que faz proxy same-origin ao
gateway), todas as URLs de front-channel derivam dela. Roteiro de subida no [README § 2b](../README.md).

| Variável        | Onde é lida                        | Default | Observação                                                                                       |
| --------------- | ---------------------------------- | ------- | ------------------------------------------------------------------------------------------------ |
| `PUBLIC_ORIGIN` | `docker-compose.deploy.yml` (interpolação) | — (fail-fast) | Esquema + host, **sem barra final** (ex.: `https://app.exemplo.com`). Deriva `CORS_ALLOWED_ORIGINS`, `CORS_ALLOWED_ORIGINS_AUTH`, `OAUTH_AUTHORIZATION_URI`, `OAUTH_REDIRECT_URI`, `OAUTH_END_SESSION_URI`, `POST_LOGOUT_REDIRECT_URI`, `API_BASE_URL`, `AUTH_URL`, `AUTH_TOKEN`, `OAUTH_CLIENT_REDIRECT_URIS` e `OAUTH_CLIENT_POST_LOGOUT_URIS`. Sem ela o overlay não sobe (`${PUBLIC_ORIGIN:?}`). |
| `PUBLIC_HOST`   | `docker-compose.deploy.yml` (serviço `assert-env`) | — (fail-fast) | O mesmo valor sem o esquema. Usada pelo `cloudflared tunnel route dns` (o `CNAME` do hostname público) e **verificada em runtime**: o init-container `assert-env` aborta a stack se `PUBLIC_HOST` divergir do hostname implícito em `PUBLIC_ORIGIN` (ADR-019 — a divergência silenciosa entre as duas foi o Elo 1 que derrubou o login). Não é mais documental. |
| `TUNNEL_ID`     | `docker-compose.deploy.yml` (interpolação) | — (fail-fast) | UUID impresso por `cloudflared tunnel create`, passado como argumento do `run`. Mora no `.env` — e não no `infra/cloudflared/config.yml` — porque o compose interpola `${VAR}` no `command` mas **não** dentro de um YAML montado, e o repositório não carrega identificadores do deploy real. Sem ela o overlay não sobe (`${TUNNEL_ID:?}`). |

> **`AUTH_ISSUER` e `AUTH_ISSUER_URI` continuam internos** — o JWT é validado pela rede Docker, não
> pela origem pública. Só `OAUTH_AUTHORIZATION_URI`, `OAUTH_END_SESSION_URI` e as URLs do Swagger
> são front-channel (navegadas pelo browser) e por isso apontam ao domínio.
>
> **Trocar `PUBLIC_ORIGIN` não reconcilia o `gateway-client`:** os redirect URIs são semeados no
> Postgres e o seed é idempotente (`findByClientId` → `save` só se ausente). Ver [SECURITY.md](SECURITY.md).

## Borda e IP do cliente (lockout / rate limit)

Fonte de IP do **lockout** (auth-server) e do **rate limiting** (gateway). Não-falsificável sob
Cloudflare Tunnel (ADR-010, item 1.2 RELATORIOA): o header confiável é a fonte primária; o
`forward-headers-strategy` é o fallback. Detalhe em [SECURITY.md](SECURITY.md).

| Variável                          | Serviço(s)            | Default dev       | Observação                                                                                                  |
| --------------------------------- | --------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------- |
| `SERVER_FORWARD_HEADERS_STRATEGY` | gateway · auth-server | `framework`       | Faz o `remoteAddress`/`getRemoteAddr()` refletir o `X-Forwarded-For` sanitizado pela borda (fallback do IP). Default `framework` na **base** do config-server (antes só no overlay de deploy); seguro sem proxy. |
| `TRUSTED_CLIENT_IP_HEADER`        | gateway · auth-server | `CF-Connecting-IP`| Header de IP confiável, **fonte primária** do particionamento por IP. A Cloudflare sobrescreve `CF-Connecting-IP`. **Esvaziar/trocar** em deploy não-Cloudflare (cai no XFF via forward-headers). |
| `GATEWAY_TRUSTED_PROXIES`         | gateway               | `10\..*\|172\.(1[6-9]\|2[0-9]\|3[01])\..*\|192\.168\..*` | Regex (avaliado com `Pattern.matches`, ancorado) contra o IP do peer imediato. Registra `XForwardedHeadersFilter` e `ForwardedHeadersFilter` no SCG 5.0.0 (sem esta propriedade, nenhum dos dois filtros é ativado — regressão SCG 5.0.0, ADR-019). Default: RFC1918 amplo (machine-agnostic). **Seguro apenas com G10 fechado** (portas do gateway/interface removidas da base do compose). |
| `LOCKOUT_MAX_ATTEMPTS`            | auth-server           | `5`               | Falhas (conta+IP) antes do lockout.                                                                          |
| `LOCKOUT_DURATION`                | auth-server           | `15m`             | Janela/TTL do lockout no Redis.                                                                              |
| `TOKEN_REVOCATION_ENABLED`        | user-service · gateway · auth-server | `true` | Liga/desliga a revogação ativa de token (ADR-017). `false` torna a escrita e a checagem do epoch no-op.       |
| `TOKEN_REVOCATION_KEY_PREFIX`     | user-service · gateway · auth-server | `revoke:user:` | Prefixo da chave do epoch de revogação no Redis. **Deve casar entre os três serviços** (fonte única compartilhada). |
| `TOKEN_REVOCATION_TTL`            | user-service          | `75m`       | TTL do epoch de revogação (`security.revocation.ttl`) — só o user-service grava. Deve ser **≥ a vida máxima do refresh token**: depois disso não há token vivo anterior à revogação e a marca se auto-limpa. |

## Observabilidade

| Variável                                       | Serviço(s)                          | Compose                              | Observação                                  |
| ---------------------------------------------- | ----------------------------------- | ------------------------------------ | ------------------------------------------- |
| `MANAGEMENT_TRACING_EXPORT_ZIPKIN_ENDPOINT`    | user-service · gateway · auth-server | `http://zipkin:9411/api/v2/spans`    | Endpoint do Zipkin para exportar spans.     |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`      | user-service · gateway · auth-server | `1.0`                                | Probabilidade de sampling de tracing. Default dev `1.0` (100%) nos `*.yml` do config-server; em **prod** reduza para conter custo/volume (ex.: `0.1`). |
| `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SESSION`    | gateway (se setada)                 | — (não setada)                       | Toggle de debug (dev) da sessão. **Não está em nenhum compose nem YAML** — é uma env que o Spring Boot reconhece se você a exportar, não algo que o projeto injete. |
| `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY_WEB` | gateway (se setada)               | — (não setada)                       | Idem, para o Spring Security.               |

**Storage do Zipkin** (container `zipkin`, só `docker-compose`). O Zipkin roda **in-memory por
default** (`STORAGE_TYPE=mem`) — adequado a dev, mas os traces somem no restart. Para
persistência em prod, aponte-o para um **Elasticsearch externo** (o base não sobe um ES para
não pesar a stack local; backend é exercício do consumidor do blueprint). As chaves abaixo são
host vars com prefixo `ZIPKIN_` que o compose injeta nos nomes nativos do container
(`STORAGE_TYPE`/`ES_HOSTS`/`ES_USERNAME`/`ES_PASSWORD`):

| Variável               | Container | Default dev | Observação                                                        |
| ---------------------- | --------- | ----------- | ---------------------------------------------------------------- |
| `ZIPKIN_STORAGE_TYPE`  | `zipkin`  | `mem`       | `mem` (in-memory) ou `elasticsearch` em prod.                    |
| `ZIPKIN_ES_HOSTS`      | `zipkin`  | _vazio_     | URL(s) do Elasticsearch (ex.: `https://es.exemplo.com:9200`).    |
| `ZIPKIN_ES_USERNAME`   | `zipkin`  | _vazio_     | Usuário do ES (se autenticado).                                  |
| `ZIPKIN_ES_PASSWORD`   | `zipkin`  | _vazio_     | Senha do ES (se autenticado).                                    |

## Front-end

| Variável        | Serviço(s)      | Default dev | Observação                                                          |
| --------------- | --------------- | ----------- | ------------------------------------------------------------------- |
| `VITE_API_URL`  | login-interface | _vazio_     | Em dev/Docker fica vazio — chamadas relativas via proxy same-origin. |

## Containers de infraestrutura (só `docker-compose`)

Consumidas pelos próprios containers de banco na inicialização, não pelos serviços Spring:

> **Corrigido em 2026-08-04.** Esta tabela listava valores literais (`auth_service`,
> `auth_1234321`, `user_service`, `user_1234321`) que **não existem em lugar nenhum do
> repositório** — eram resquício de antes da migração para Docker secrets ([[ADR-009]]). As senhas
> não são mais variáveis de ambiente: vêm de `*_FILE` apontando para `/run/secrets/`.

| Variável                      | Container              | Origem / valor real | Observação                                    |
| ----------------------------- | ---------------------- | ------------------- | --------------------------------------------- |
| `POSTGRES_DB`                 | `auth-postgres`        | `authdb` (literal no compose) |                                     |
| `POSTGRES_USER`               | `auth-postgres`        | `${POSTGRES_USER}` do `.env` — template: `changeme` | Sem `:-default` no compose; se faltar no `.env`, vai vazia. |
| `POSTGRES_PASSWORD_FILE`      | `auth-postgres`        | `/run/secrets/POSTGRES_PASSWORD` | **Não é `POSTGRES_PASSWORD`.** Default de dev gerado pelo `gen-secrets.sh`: `postgres-dev-secret`. O mesmo secret é montado no `authorization-server` com *target* renomeado para `AUTH_DB_PASSWORD` (configtree). |
| `MONGO_USER`                  | `mongo-1`, `mongo-init`, `mongodb-exporter` | `${MONGO_USER}` do `.env` — template: `root` | Injetada como `MONGO_INITDB_ROOT_USERNAME` no `mongo-1`. Só no nó primário; secundários recebem por replicação. |
| `MONGO_INITDB_ROOT_PASSWORD_FILE` | `mongo-1`          | `/run/secrets/MONGO_PASSWORD` | **A chave `MONGO_INITDB_ROOT_PASSWORD` não existe.** Default de dev: `mongo-dev-secret`. O `mongo-init` lê o mesmo secret via `$(cat ...)`. |
| `GRAFANA_ADMIN_USER`          | `grafana`              | `${GRAFANA_ADMIN_USER}` do `.env` | Injetada como `GF_SECURITY_ADMIN_USER`. A senha vem do secret via `GF_SECURITY_ADMIN_PASSWORD__FILE`. |
| `WEB_HOST_PORT`               | `interface`            | `5173`          | Porta do **host** da borda pública (mapeia para `:80` do container). Dev usa `5173`; prod tipicamente `80`. Declarada no `docker-compose.override.yml` — na base prod-safe a interface **não é publicada** no host (G10/ADR-019). |

## Limites de recursos (CPU / memória)

Cada serviço do `docker-compose.yml` declara teto e reserva de recursos para que um serviço
com leak não consuma o host inteiro e derrube os vizinhos. Usamos as chaves de nível de
serviço **`cpus` / `mem_limit` / `mem_reservation`** (não o bloco `deploy.resources`):
`docker compose up` (v2) as aplica de forma determinística **fora do Swarm**, enquanto parte
de `deploy:` só vale em modo Swarm. Validar com `docker compose -f docker-compose.yml config`
(exit 0).

- **`cpus`** — teto de CPU (fração de núcleos). Limite, não reserva.
- **`mem_limit`** — teto de memória; o container é morto (OOMKilled) se ultrapassar.
- **`mem_reservation`** — alvo de memória usado pelo scheduler para distribuir os containers
  (soft); omitido nos serviços pequenos/efêmeros.

Os valores são **defaults de dev-blueprint**: `mem_limit` folgado para não causar OOMKill no
boot. Ajuste conforme a carga real do consumidor do blueprint.

> **O heap e o coletor dos apps Spring são explícitos, não ergonômicos.** A JVM 21 escolhe
> sozinha com base nos limites do container, e escolhe mal quando eles são pequenos: com
> `cpus: 1.0` ela reporta `Effective CPU Count = 1` e, junto do `mem_limit` abaixo de 1792m,
> classifica o container como *client-class machine* — o que seleciona **`UseSerialGC`**
> (stop-the-world, thread única) e heap de 25% (256m). Por isso o `x-spring-app-env` fixa
> `JAVA_TOOL_OPTIONS=-XX:+UseG1GC -XX:MaxRAMPercentage=60`, e a classe **App JVM** usa o perfil
> `x-res-jvm` com **`cpus: 2.0`** — o G1 só rende com core sobrando para a marcação concorrente,
> e a quota maior também alivia o throttling do CFS. O `MaxRAMPercentage` é 60 e não 70 porque
> o overhead non-heap medido (metaspace + code cache + stacks + buffers) é ~150m: 70% de 1024m
> deixaria o total em ~870m de um teto de 1024m, margem fina demais para OOM kill.
>
> `x-res-jvm` é **deliberadamente separado** de `x-res-app`, que serve os nós Mongo — fundir os
> dois dobraria a quota de CPU do MongoDB sem motivo.

| Classe                | Serviços                                            | `cpus` | `mem_limit` | `mem_reservation` |
| --------------------- | --------------------------------------------------- | ------ | ----------- | ----------------- |
| App JVM (pesado, `x-res-jvm`) | `gateway`, `authorization-server`, `user-service`, `notification-service` | 2.0 (`APP_CPUS`) | 1024m (`APP_MEM_LIMIT`) | 512m (`APP_MEM_RESERVATION`) |
| App JVM (leve)        | `config-server`, `discovery-server-1/2`             | 0.75   | 512m        | 256m              |
| MongoDB (`x-res-app`) | `mongo-1/2/3`                                        | 1.0    | 1024m       | 512m              |
| PostgreSQL            | `auth-postgres`                                      | 0.75   | 512m        | 256m              |
| Redis (data)          | `redis-1/2/3`                                        | 0.5    | 256m        | 64m               |
| Redis Sentinel        | `redis-sentinel-1/2/3`                               | 0.25   | 128m        | —                 |
| Zipkin (in-memory)    | `zipkin`                                             | 0.5    | 512m        | 256m              |
| Prometheus            | `prometheus`                                         | 0.5    | 512m        | 256m              |
| Grafana               | `grafana`                                            | 0.5    | 256m        | 128m              |
| Exporters             | `mongodb-exporter`, `postgres-exporter`, `redis-exporter` | 0.25 | 128m    | —                 |
| Nginx (SPA / borda)   | `interface`                                          | 1.0    | 128m        | 64m               |
| Nginx (interno)       | `config-lb`                                          | 0.25   | 64m         | —                 |
| One-shot              | `mongo-init`                                         | 0.5    | 256m        | —                 |

> A soma das **reservas** é o que dimensiona o host; os `mem_limit` são tetos de contenção, não
> memória pré-alocada. O `docker-compose.override.yml` (dev) só republica portas — os limites do
> base valem em dev e no deploy prod-like. A tabela acima descreve o piso **`ha`** (26 serviços,
> ~5,3 GB de reserva); o **piso mínimo** (19 serviços, default) tira 2 nós Mongo, 2 Redis, 2
> sentinels e 1 discovery, caindo para ~3,5 GB.

> **Os três valores da classe `x-res-jvm` valem POR RÉPLICA** (ADR-024). Foram calibrados para um
> processo por serviço; com `--scale`, N réplicas multiplicam reserva e quota. Num host de 12 vCPU
> / 15 GB isso esgota rápido — daí `APP_CPUS` / `APP_MEM_LIMIT` / `APP_MEM_RESERVATION`. Ao
> baixar `APP_CPUS`, **não desça abaixo de ~1.5**: com quota de 1.0 a JVM se classifica como
> *client-class machine* e escolhe `UseSerialGC`, anulando o `-XX:+UseG1GC` do `JAVA_TOOL_OPTIONS`
> — é exatamente o problema que motivou separar `x-res-jvm` de `x-res-app`.

### Pools de conexão (tetos de escala)

| Variável | Serviço | Default | Teto que governa |
| --- | --- | --- | --- |
| `AUTH_DB_POOL_SIZE` | authorization-server | 8 | N réplicas × 8 ≤ `max_connections` do Postgres (default **100**; medido no ambiente: 10 do pool + 6 de exporter/internas, ~84 livres → até 10 réplicas) |
| `MONGO_MAX_POOL_SIZE` | user-service | 50 | Limitado pela **memória** do nó Mongo, não por `max_connections`: ~1 MB de pilha por conexão contra `mem_limit: 1024m` |

> Sem o `AUTH_DB_POOL_SIZE` explícito valia o default do Hikari (10) e a ~9ª réplica de
> auth-server encontrava o Postgres esgotado — com o sintoma (`FATAL: sorry, too many clients
> already`) aparecendo no auth-server, longe da causa, e só sob a escala que deveria estar
> ajudando. O `MONGO_MAX_POOL_SIZE` vive no `user-service.yml` e **não** dentro da `MONGODB_URI`
> de propósito: a URI é Docker secret gerado pelo `gen-secrets.sh`, e mudar o pool não pode
> exigir regerar segredo.

### Piso mínimo e crescimento (ADR-024)

`docker compose up` sem argumento sobe o **piso mínimo**: 19 serviços, com Mongo em replica set
`rs0` de **um membro** e Redis com **um** nó e **um** Sentinel. Não é standalone de propósito — a
`MONGODB_URI` (`replicaSet=rs0`) e o `spring.data.redis.sentinel.*` dos clientes são idênticos no
piso e no topo, então crescer nunca reconfigura cliente.

```bash
docker compose up -d                                    # piso mínimo (19 serviços)
docker compose --profile ha up -d                       # + redundância de dados (26)
docker compose -f docker-compose.yml up -d \
  --scale gateway=2 --scale user-service=2              # + réplicas de aplicação
```

> **`--scale` não funciona com o `docker-compose.override.yml`** (que é o default de
> `docker compose up`, daí o `-f` explícito acima): ele publica portas fixas no host e a 2ª
> réplica falha no bind. Listas de `ports:` são **concatenadas** no merge entre arquivos, nunca
> removidas — não há overlay capaz de desfazê-las.

**Crescer o Mongo é automático; encolher é manual.** O `infra/mongo/rs-reconcile.sh` descobre por
DNS os nós alcançáveis e faz `rs.reconfig` aditivo, então `--profile ha` sozinho leva o replica set
de 1 para 3 membros no mesmo volume. Ele **nunca remove** membro: fazê-lo por lookup que falhou
significaria que uma falha transitória de DNS num restart derrubaria o quorum.

### Encolher de `ha` para o piso mínimo (runbook)

A ordem importa, e errá-la tem um custo específico: **a config do `rs0` vive no volume, não no
compose**. Depois de um ciclo `--profile ha` ela fica com 3 membros e continua com 3 quando
mongo-2/3 somem. Sozinho numa config de 3 votantes, mongo-1 não alcança maioria, assume
**SECONDARY** e passa a **recusar escrita** (`NotWritablePrimary`) — com leitura funcionando e
healthcheck verde. Verificado em 2026-08-07: `docker compose up` sobre um volume nesse estado sobe
um Mongo somente-leitura.

```bash
# 1. Com o replica set AINDA em maioria (profile ha no ar), remova os membros no PRIMARY:
docker exec <mongo-1> mongosh -u "$MONGO_USER" -p "$(cat secrets/MONGO_PASSWORD)" \
  --authenticationDatabase admin --quiet \
  --eval 'rs.remove("mongo-2:27017"); rs.remove("mongo-3:27017"); rs.conf().members.length'

# 2. Confirme que redis-1 é o master corrente (ver nota abaixo):
docker exec <redis-sentinel-1> sh -c 'REDISCLI_AUTH=$(cat /run/secrets/REDIS_PASSWORD) \
  redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster'

# 3. Derrube COM o profile e SEM -v (o -v apagaria Mongo e Postgres):
docker compose --profile ha down

# 4. Suba o piso:
docker compose up -d
```

O **passo 1 é o que não pode faltar**; o `rs.remove` precisa rodar enquanto ainda há maioria para
aceitar o `rs.reconfig`. O **passo 2** existe porque `infra/redis/sentinel.conf` fixa `quorum 2`:
no piso, com um único Sentinel, failover é impossível por construção — encolher com o master fora
de `redis-1` deixaria o piso sem master. O **profile no `down`** (passo 3) é obrigatório: sem ele
os serviços do profile ficam órfãos rodando.

**Se a ordem for violada, o `rs-reconcile.sh` avisa.** Ele compara os membros configurados com os
alcançáveis e, quando estes não formam maioria, imprime o diagnóstico com os `rs.remove` exatos a
executar e **sai com erro**. Como `user-service` depende dele com
`condition: service_completed_successfully`, a aplicação **não sobe** contra um Mongo
somente-leitura — falha alta em vez de aceitar cadastros que morreriam em 500. O mesmo mecanismo
cobre a corrida de startup do `--profile ha` (se mongo-2 ainda não resolve no DNS, o job repete via
`restart: on-failure` e passa quando o nó aparece).

**O que o encolhimento preserva e o que não preserva.** Mongo e Postgres têm volumes **nomeados** e
atravessam o `down` intactos — medido: `users`, `auditLogs`, o registro do `gateway-client` e as
autorizações OAuth sobreviveram, e a escrita voltou a funcionar no piso. O **Redis não**: o compose
não declara volume nomeado para ele, e o `VOLUME /data` da imagem gera um volume **anônimo** por
container, que o container seguinte não reaproveita. Sessão de login, cache e contadores de rate
limit são perdidos — todo mundo precisa logar de novo. Isso vale para **qualquer** `down`, não é
custo específico do encolhimento, mas é visível ao usuário e não deve ser confundido com falha.

### Manobras de escala em deploy

Em deploy, **todo** comando carrega os dois `-f` — sem eles o Compose auto-carrega o
`docker-compose.override.yml` (portas de dev) e trata `cloudflared`/`assert-env` como órfãos. Os
comandos abaixo assumem o atalho:

```bash
dcd() { docker compose -f docker-compose.yml -f docker-compose.deploy.yml "$@"; }
```

**Duas regras do Compose decidem o resultado de tudo o que segue** (medidas na v2.27):

1. **`up -d` sem `--scale` devolve o serviço a 1.** Não é incremental: `--scale user-service=3`
   seguido de um `up -d` qualquer deixa **uma** réplica.
2. **`up -d` sem `--profile ha` não derruba os nós de redundância.** Eles continuam rodando, fora
   do controle do comando — é daí que vêm os órfãos.

Juntas: **todo `up` tem de carregar o estado desejado inteiro — profile _e_ scale.** Omitir um não
significa "mantenha como está"; significa duas coisas diferentes, e nenhuma delas é essa.

| # | Manobra | Comando | O que acontece |
|---|---------|---------|----------------|
| a | Subir o piso | `dcd up -d --build` | 19 serviços; `mongo-init`/`assert-env` saem com 0 |
| a | Derrubar o piso | `dcd down` | Para tudo; **preserva** os volumes nomeados (Mongo/Postgres) |
| b | Piso → redundância de dados | `dcd --profile ha up -d` | 26 serviços; o `rs0` cresce de 1 para 3 membros sozinho (`rs-reconcile.sh`) |
| b | Redundância → piso | runbook acima, passos 1–2, e então `dcd --profile ha down` + `dcd up -d` | O `rs.remove` **antes** do `down` é o que impede o Mongo somente-leitura |
| c | Piso → mais instâncias | `dcd up -d --scale user-service=3 --scale gateway=2` | Réplicas de aplicação; Eureka distribui em round-robin (~25s de convergência) |
| c | Instâncias → piso | `dcd up -d` | Regra 1: **todos** os serviços voltam a 1 |
| c | Derrubar réplicas de um serviço só | `dcd up -d --scale user-service=1` | Os demais `--scale` omitidos também voltam a 1 |
| d | Redundância + instâncias | `dcd --profile ha up -d --scale user-service=3` | Os dois eixos são ortogonais e convivem |
| d | Remover instâncias, manter a redundância | `dcd --profile ha up -d --scale user-service=1` | Sem o `--profile ha` os nós `ha` viram órfãos (regra 2) |
| e | Instâncias + subir redundância | `dcd --profile ha up -d --scale user-service=3` | **Repetir o `--scale` é obrigatório** — sem ele o mesmo comando derruba as réplicas (regra 1) |
| e | Remover a redundância, manter instâncias | runbook acima, passos 1–2, e então `dcd --profile ha down` + `dcd up -d --scale user-service=3` | O caminho verificado passa por `down`; o `--scale` no `up` traz as réplicas de volta |

**Em deploy, `--scale` não tem restrição de porta.** A ressalva do início desta seção
(`--scale` exige `-f docker-compose.yml`) é sobre **dev**: quem publica porta fixa é o override. A
base não publica porta nenhuma, e no deploy os únicos `ports:` são `grafana`/`prometheus`/`zipkin`
presos a `127.0.0.1` — esses três não escalam, o resto sim, inclusive `gateway` e `interface`.
Eixos escaláveis na prática: os 4 apps Spring e o `config-server`. Antes de escalar o
`authorization-server`, confira o teto de conexões em [Pools de conexão](#pools-de-conexão-tetos-de-escala).

**Variante sem downtime para as linhas `b`/`e`** — remover só os containers de redundância, deixando
o resto no ar:

```bash
dcd rm -sf mongo-2 mongo-3 redis-2 redis-3 redis-sentinel-2 redis-sentinel-3 discovery-server-2
```

Vale o mesmo pré-requisito (`rs.remove` e checagem do master do Redis antes), mas este caminho
**não foi exercitado** — o verificado ponta a ponta, com preservação de dado medida, é o
`down`/`up` da tabela. O `discovery-server-1` vai logar erro de replicação de peer até o próximo
restart.

> **Por que o `interface` saiu da classe do `config-lb`.** Os dois são nginx, mas têm papéis
> opostos sob hostname único: o `config-lb` só balanceia duas instâncias de config-server na
> rede interna, enquanto o `interface` é o **funil de 100% do tráfego público** — o túnel
> entrega nele, não no gateway (ver `CLAUDE.md § Deploy via Cloudflare Tunnel`). Era o serviço
> de menor quota do compose no único salto que toda requisição atravessa. O serviço também
> declara **`NGINX_ENTRYPOINT_WORKER_PROCESSES_AUTOTUNE: "1"`**, que aciona o
> `/docker-entrypoint.d/30-tune-worker-processes.sh` da imagem oficial: ele lê a quota de CPU do
> **cgroup** e reescreve `worker_processes` de acordo (com `cpus: 1.0` → 1 worker, que é o
> idiomático para nginx — um worker satura um núcleo). Sem isso vale o `auto` do `nginx.conf`,
> que conta as CPUs do **host** e ignora a quota: foram medidos **12 workers** disputando um
> quarto de núcleo, puro custo de troca de contexto.
>
> **Armadilha:** não tente passar `worker_processes` via `-g` no `CMD`. É diretiva de contexto
> *main* (o `nginx.conf` do projeto é copiado para `conf.d/default.conf`, contexto *server*),
> mas o `nginx.conf` **base da imagem já a declara na linha 3** — o `-g` vira uma segunda
> declaração e o nginx aborta na subida com `"worker_processes" directive is duplicate`.
> Derivar da quota, além de funcionar, mantém workers e `cpus` em sincronia automaticamente.
