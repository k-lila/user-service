# users

[![CI](https://github.com/k-lila/user-service/actions/workflows/ci.yml/badge.svg)](https://github.com/k-lila/user-service/actions/workflows/ci.yml)

API REST de gerenciamento de usuários construída com arquitetura de microsserviços — blueprint de um sistema de usuários (autenticação, registro e controle de acesso) pronto para produção multi-instância, sobre o qual outras camadas de domínio podem ser adicionadas.

O front-end segue o padrão **BFF**: o gateway é o cliente OAuth2, o SPA usa sessão por cookie e nunca manuseia JWT — o token fica na sessão do gateway (Redis) e é relayado aos serviços internos.

**Stack:** Java 21 · Spring Boot 4 · Spring Cloud 2025 · MongoDB · PostgreSQL · Redis · OAuth2 + PKCE · React 19 · Docker Compose

---

## Arquitetura

```
login-interface (React)
        │
        ▼
    gateway :8081          ← único ponto de entrada externo
    ├── /v1/users/register   → user-service :8090   (público, rate limit por IP)
    ├── /v1/users/verify-email → user-service        (público, pré-sessão)
    ├── /v1/users/**         → user-service :8090   (sessão BFF → tokenRelay)
    ├── /v1/admin/**         → user-service :8090   (ROLE_ADMIN, checado downstream)
    ├── /oauth2/**           → authorization-server :8082
    ├── /login               → authorization-server :8082  (formulário do IdP)
    ├── /default-ui.css      → authorization-server :8082  (CSS do formulário)
    ├── /connect/**          → authorization-server :8082  (RP-Initiated Logout)
    └── /v3/api-docs/**      → user-service · authorization-server  (doc agregado)
              │
              ├── authorization-server ── PostgreSQL (estado OAuth) · Redis (sessão)
              ├── user-service ────────── MongoDB replica set rs0 · Redis (cache + rate limit)
              ├── config-lb :8888 ─────── config-server ×2 (Spring Cloud Config HA)
              ├── discovery-server ×2 ─── Eureka HA (:9091 / :9092)
              ├── Redis Sentinel ──────── 3 nós + 3 sentinels (sessão, cache, rate limit)
              └── Zipkin / Prometheus / Grafana (observabilidade)
```

**Fluxo de login (resumo):** o SPA redireciona ao gateway, que inicia o OAuth2 (authorization_code + PKCE) contra o authorization-server; este valida as credenciais consultando o user-service por um canal interno e emite o JWT; o gateway guarda o token na sessão (cookie `SESSION`, HttpOnly) e o repassa aos serviços a cada request.

---

## Estrutura de pastas e arquivos

```
.
├── authorization-server/         # OAuth2 Authorization Server (login, emissão de JWT)
├── config-server/                # Config centralizada (YAMLs dos serviços em resources/config)
├── discovery-server/             # Service discovery (Eureka)
├── gateway/                      # Spring Cloud Gateway — borda, BFF, rate limiting, CSRF
├── user-service/                 # Domínio de usuários (CRUD, MongoDB, cache Redis)
├── notification-service/         # Envio de e-mail de verificação (stateless, SMTP)
├── login-interface/              # SPA React (Vite + TailwindCSS)
├── infra/                        # Configs de infraestrutura:
│   ├── secrets/                  #   gen-secrets.sh (gera os Docker secrets)
│   ├── jwk/                      #   gen-keys.sh (par de chaves JWT)
│   ├── config-lb/                #   nginx LB dos config-servers
│   ├── mongo/                    #   keyfile do replica set
│   ├── redis/                    #   sentinel.conf
│   ├── grafana/                  #   dashboards e datasources provisionados
│   ├── cloudflared/              #   ingress rules do named tunnel (versionadas)
│   └── prometheus.yml            #   alvos de scrape
├── docs/                         # Documentação técnica detalhada
├── docker-compose.yml            # Base prod-safe (publica só a borda)
├── docker-compose.override.yml   # Deltas de dev (republica portas internas; auto-carregado)
├── docker-compose.deploy.yml     # Overlay opcional — Cloudflare Tunnel
└── .env.example                  # Template do .env (contrato de variáveis, comentado)
```

---

## Pré-requisitos

| Ferramenta              | Versão mínima | Necessário para            |
| ----------------------- | ------------- | -------------------------- |
| Docker + Docker Compose | 24+           | Execução (única suportada) |

---

## Execução

### 0. Gerar os secrets e a chave JWK (obrigatório, uma vez)

```bash
infra/secrets/gen-secrets.sh        # defaults de DEV (rode uma vez antes do up)
```

> Em dev **manual** (sem Docker), gere só o par JWK no classpath do auth-server:
> `infra/jwk/gen-keys.sh authorization-server/src/main/resources/keys`.

### 1. Criar o `.env` (obrigatório)

O `.env` guarda apenas as **identidades não-segredo** (usuários, hostnames públicos) interpoladas no compose — os segredos vêm do passo 0.

> **Atenção:** a base **não** tem fail-fast. As interpolações do `docker-compose.yml`
> (`${MONGO_USER}`, `${POSTGRES_USER}`, `${CONFIG_SERVER_USERNAME}`, `${GRAFANA_ADMIN_USER}`) são
> `${VAR}` simples — sem `.env`, o Compose as substitui por **string vazia com um warning** e a
> stack sobe até o Mongo/Postgres recusarem a credencial vazia. O `docker compose config -q` do CI
> passa assim. O fail-fast de verdade (`${VAR:?}`) existe só no overlay de deploy
> (`PUBLIC_ORIGIN`, `PUBLIC_HOST`, `TUNNEL_ID`). Ou seja: preencher o `.env` é obrigatório, mas
> quem avisa é o banco, não o Compose.

```bash
cp .env.example .env   # e preencha os valores (em dev, qualquer valor consistente serve)
```

### 2a. Desenvolvimento local (HTTP, modo padrão)

```bash
docker compose up -d --build      # base + docker-compose.override.yml (auto-carregado)

docker compose logs -f            # acompanhar logs
docker compose down -v            # derrubar (incluindo volumes)
```

Acesso: front-end em http://localhost:5173 · API em http://localhost:8081.

Para um deploy **prod-like** (só a borda exposta, sem as portas de dev):

```bash
docker compose -f docker-compose.yml up -d --build   # ignora o override
# URLs públicas via .env — ver o bloco comentado no .env.example
```

### 2b. Deploy na própria máquina via Cloudflare Tunnel (domínio fixo)

**Named tunnel + domínio próprio.** O túnel entrega em `interface:80` (o nginx do SPA), que faz
proxy same-origin ao gateway — browser e API na **mesma origem**, e nem o gateway nem o
authorization-server ficam alcançáveis de fora. `${PUBLIC_ORIGIN}` designa a origem pública
(ex.: `https://app.exemplo.com`) e `${PUBLIC_HOST}` o hostname sem esquema; os valores reais vivem
só no `.env` (gitignorado).

#### Pré-requisitos na Cloudflare (uma vez, fora do repositório)

O túnel é **locally-managed**: criado pela CLI, não pelo painel. O Zero Trust — onde ficariam o
token e os *public hostnames* — exige cadastro de cartão de crédito mesmo no plano free, então o
roteamento da borda vive em [`infra/cloudflared/config.yml`](infra/cloudflared/config.yml),
versionado.

**1. Delegar o domínio à Cloudflare** (pode levar horas — comece por aqui). No painel do
registrador, troque os servidores DNS pelos dois nameservers que a Cloudflare atribuiu e **não
publique registros DS** (DNSSEC desligado; se a zona ficar assinada pelo registrador enquanto as
respostas vêm da Cloudflare, o domínio inteiro dá `SERVFAIL`). Verifique antes de seguir:

```bash
dig NS <dominio> @1.1.1.1 +short   # deve retornar os nameservers da Cloudflare
dig DS <dominio> @1.1.1.1 +short   # deve sair VAZIO (DNSSEC desligado)
```

> **Use um resolver público (`@1.1.1.1`), não o do sistema:** o resolver local guarda a delegação
> antiga por até uma hora e devolve os nameservers anteriores mesmo com a troca já publicada —
> `sudo resolvectl flush-caches` resolve. E **não** consulte o servidor do TLD (`@a.dns.br`) com
> `+short`: ele responde com um *referral*, que vai na seção AUTHORITY, e `+short` imprime só a
> ANSWER — a saída sai vazia mesmo estando tudo certo. Para checar direto na fonte, rode
> `dig NS <dominio> @a.dns.br` sem `+short` e leia a AUTHORITY SECTION.

**2. Criar o túnel pela CLI.** Rode o container **como root** (`--user 0:0`) e devolva a posse dos
arquivos no fim. Ver a armadilha de permissão logo abaixo:

```bash
mkdir -p ~/.cloudflared
CFD='docker run --rm --user 0:0 -e HOME=/home/nonroot -v '"$HOME"'/.cloudflared:/home/nonroot/.cloudflared cloudflare/cloudflared:latest'

docker run --rm -it --user 0:0 -e HOME=/home/nonroot \
  -v "$HOME/.cloudflared:/home/nonroot/.cloudflared" \
  cloudflare/cloudflared:latest tunnel login       # autorize o domínio no browser

eval $CFD tunnel create user-service               # ANOTE o UUID impresso
eval $CFD tunnel route dns user-service <PUBLIC_HOST>

sudo chown -R "$(id -u):$(id -g)" ~/.cloudflared   # gen-secrets.sh precisa ler o JSON
```

O `create` grava `~/.cloudflared/<UUID>.json` (o *credentials-file*) e o `route dns` cria o
`CNAME` proxied de `${PUBLIC_HOST}` para o túnel.

> **Por que root, e por que `--user "$(id -u):$(id -g)"` NÃO funciona:** na imagem distroless,
> `/home/nonroot` é `drwx------` do uid **65532**. Rodando com qualquer outro uid não-root o
> processo não consegue sequer atravessar esse diretório, e o `login` morre com
> `open /home/nonroot/.cloudflared: permission denied` — **independentemente** de quem seja o dono
> do diretório montado do host, e sem `chown` que resolva. Pior: `tunnel list` engole esse erro e
> reporta apenas "cert.pem não encontrado", o que despista o diagnóstico. Rodar como root
> atravessa e escreve; o `chown` final devolve os arquivos ao seu usuário. (Se preferir não usar
> root: omita `--user`, deixe o uid padrão 65532 da imagem e faça
> `sudo chown -R 65532:65532 ~/.cloudflared` antes — mas você vai precisar do `chown` de volta
> depois, de qualquer jeito.)

**3. Alimentar o `.env` e os segredos** com o que o passo 2 produziu: `TUNNEL_ID=<UUID>` no `.env`
e o JSON no Docker secret (comando completo no passo 2 da subida, abaixo). **Assegure-se de que
`PUBLIC_HOST` seja idêntico ao hostname de `PUBLIC_ORIGIN` sem o `https://`** — o serviço
`assert-env` verifica isso automaticamente e aborta a subida se divergirem (mensagem clara),
mas é melhor corrigir antes do que depender do fail-fast em `docker compose up`.

> **Swagger:** o controle previsto era o **Cloudflare Access** (e-mail único + OTP) sobre
> `${PUBLIC_HOST}/swagger-ui/*` e `/v3/api-docs/*` — mas o Access faz parte do Zero Trust e está
> bloqueado pela mesma exigência de cartão. Desde [ADR-020](docs/adr/ADR-020-swagger-atras-da-sessao.md)
> isso deixou de importar: as duas rotas **exigem a sessão OAuth2 do próprio BFF** (anônimo leva 302
> para o login em `/swagger-ui/**` e 401 em `/v3/api-docs/**`). Se o Access for habilitado um dia,
> **não** o aplique em `/v1/**` — são XHR do SPA e quebrariam.

#### Subida (a ordem é obrigatória)

Regenerar segredos → `down -v` → `up`. Subir antes de regenerar cria os volumes com as senhas
antigas (`POSTGRES_PASSWORD`/`MONGO_PASSWORD` só são aplicadas na **primeira** inicialização do
volume) e obriga a repetir o ciclo.

```bash
# 1. Zerar os volumes. Necessário porque os redirect URIs do gateway-client são semeados no
#    Postgres e o seed é IDEMPOTENTE, sem reconciliação — trocar de domínio não atualiza nada.
docker compose down -v

# 2. Regenerar TODOS os segredos com valores fortes (os defaults do gen-secrets.sh são públicos)
CONFIG_SERVER_PASSWORD=$(openssl rand -hex 32) \
REDIS_PASSWORD=$(openssl rand -hex 32) \
OAUTH_CLIENT_SECRET=$(openssl rand -hex 32) \
INTERNAL_API_TOKEN=$(openssl rand -hex 32) \
POSTGRES_PASSWORD=$(openssl rand -hex 32) \
MONGO_PASSWORD=$(openssl rand -hex 32) \
GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 32) \
CLOUDFLARE_TUNNEL_CREDENTIALS=~/.cloudflared/<UUID>.json \
  infra/secrets/gen-secrets.sh

# 3. Alinhar MONGO_PASSWORD no .env ao secret gerado — o mongodb-exporter lê do env (resíduo 0.3).
#    Se divergir, o exporter fica fora do ar em silêncio. Setar também PUBLIC_ORIGIN e TUNNEL_ID.
#
#    ATENÇÃO — o gen-secrets.sh reescreve TODOS os secrets, sempre. O comando acima não exporta os
#    seis SMTP_*, então rodá-lo com SMTP real já configurado REVERTE os seis para os placeholders
#    de dev (localhost:1025, sem auth/TLS) — em silêncio, e o e-mail de verificação para de sair.
#    Se já houver SMTP real, exporte também: SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD,
#    SMTP_AUTH, SMTP_STARTTLS.

# 4. Subir — up ÚNICO (o roteiro de 3 passos com placeholder existia só por causa da URL efêmera)
export PUBLIC_ORIGIN=https://app.exemplo.com   # ou defina no .env
export TUNNEL_ID=<UUID do passo 2 dos pré-requisitos>   # ou defina no .env
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --build
```

> O `gen-secrets.sh` regenera o par JWK a cada execução — a chave de dev, que assinou tokens
> locais, é substituída automaticamente no passo 2.

#### Verificação

```bash
curl -I ${PUBLIC_ORIGIN}                                    # CSP, HSTS, Referrer-Policy presentes
curl ${PUBLIC_ORIGIN}/internal/users/email/x@y.z            # 404/403 — canal interno inalcançável
docker compose ps                                           # todos healthy
docker compose logs cloudflared | grep -i "Registered tunnel connection"   # 4 conexões
```

> `${PUBLIC_ORIGIN}/swagger-ui/*` exige sessão (ADR-020): anônimo recebe 302 para o login, e
> `/v3/api-docs/*` recebe 401. Faça um `curl` anônimo nos dois para confirmar o gate — se algum
> responder 200 sem cookie, o `permitAll()` do gateway regrediu.

E o fluxo completo no browser: registro → login → `/dashboard` → logout → retorno a
`${PUBLIC_ORIGIN}/`. Confira também que os cookies `SESSION`/`AUTHSESSION` chegam com `Secure`
e que o `CF-Connecting-IP` alimenta o rate limit (um 429 após várias tentativas de registro
confirma o particionamento por IP).

**Observabilidade neste deploy.** Grafana, Prometheus e Zipkin **não são públicos** e não têm regra
de ingress no túnel — `${PUBLIC_ORIGIN}/grafana` cai no `try_files` do SPA, não no Grafana. Os três
respondem **apenas nesta máquina**, publicados em `127.0.0.1` pelo `docker-compose.deploy.yml`:
Grafana em `http://localhost:3000`, Prometheus em `http://localhost:9090` e Zipkin em
`http://localhost:9411`. O motivo de não expor: o Grafana tem só usuário/senha, sem lockout, sem
rate limit e sem MFA, e Prometheus e Zipkin não têm autenticação alguma, ao contrário do resto do
sistema. Para
consultar de outro dispositivo sem abrir superfície pública, use uma malha privada (Tailscale/
WireGuard) em vez de rotear o túnel até ele — racional completo em `.claude/memory/decisions.md`.

> ⚠️ **O `down -v` do passo 1 não se repete depois de haver dados reais.** A partir daí, trocar de
> domínio exige `UPDATE` direcionado no Postgres ou um seed reconciliador.
>
> ⚠️ **Não abra este deploy para cadastro de terceiros.** Sem SMTP real o e-mail de verificação não
> sai e a conta fica inacessível após as 24h de grace period ([ADR-015](docs/adr/ADR-015-verificacao-email-cadastro.md));
> sem as páginas `/terms` e `/privacy`, o consentimento do [ADR-012](docs/adr/ADR-012-consentimento-lgpd-cadastro.md)
> é colhido sobre texto ilegível. O ambiente é para teste pelo próprio operador.

---

## URLs de acesso (dev)

| Serviço       | URL                                                                              |
| ------------- | -------------------------------------------------------------------------------- |
| API (gateway) | http://localhost:8081                                                            |
| Swagger UI    | http://localhost:8081/swagger-ui/index.html                                      |
| Front-end     | http://localhost:5173                                                            |
| authorization-server | http://localhost:8082                                                     |
| user-service  | http://localhost:8090                                                            |
| notification-service | http://localhost:8095                                                     |
| config-lb     | http://localhost:8888                                                            |
| Eureka        | http://localhost:9091 · http://localhost:9092                                    |
| Zipkin        | http://localhost:9411 🔒                                                          |
| Prometheus    | http://localhost:9090 🔒                                                          |
| Grafana       | http://localhost:3000 🔒 (user do `.env`, senha do secret `GRAFANA_ADMIN_PASSWORD`) |

> 🔒 **Só a partir desta máquina.** As três portas de observabilidade são publicadas presas ao
> loopback (`127.0.0.1:PORTA:PORTA` no `docker-compose.override.yml`), então `localhost` funciona
> aqui e conexões de qualquer outro host da rede são recusadas. Nenhuma delas tem lockout, rate
> limit ou MFA — Prometheus e Zipkin não têm autenticação nenhuma —, e Prometheus/Zipkin revelam
> métricas, traces, hostnames internos e topologia. Trocar por `- "3000:3000"` (sem o IP) republica
> em `0.0.0.0` e devolve o acesso à LAN inteira.

---

## Integração Contínua (CI)

A cada `push` na `main` e a cada `pull_request`, o workflow [`ci.yml`](.github/workflows/ci.yml)
roda no GitHub Actions quatro frentes em paralelo:

| Job                 | O que roda                                                                                                                      |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `backend` (matrix)  | `mvn -B verify` por módulo (6 serviços) — dispara o gate de cobertura JaCoCo; integração via Testcontainers no Docker do runner |
| `frontend`          | `npm ci` + `npm run coverage` no `login-interface` — Vitest com threshold de 80%                                                |
| `compose-validate`  | `docker compose -f docker-compose.yml config -q` — valida a topologia base                                                      |
| `smoke-test-login`  | sobe nginx + gateway + authorization-server (topologia de deploy, sem `cloudflared`) e valida 5 asserções HTTP da cadeia de login — [ADR-023](docs/adr/ADR-023-smoke-test-automatizado-login-hostname-unico.md) |

O `smoke-test-login` é o único job que exercita o container `interface`: os Testcontainers nunca
sobem o nginx e o `compose-validate` só valida sintaxe YAML. É também o mais lento — builda 4
imagens do zero. Detalhes e execução local em [docs/TESTES.md](docs/TESTES.md#smoke-test-da-topologia-de-login-adr-023).

Não há POM-pai agregador, por isso o back-end roda como **matrix** (um job por módulo).
Os relatórios (Surefire/Failsafe, JaCoCo, cobertura do Vitest) são publicados como artefatos do run.

**Gate de merge (branch protection):** a `main` exige todos os checks acima verdes antes de
aceitar merge. Para (re)aplicar a regra via API (precisa de admin no repo):

```bash
gh api -X PUT repos/k-lila/user-service/branches/main/protection \
  -H "Accept: application/vnd.github+json" \
  -f 'required_status_checks[strict]=true' \
  -f 'required_status_checks[contexts][]=backend (config-server)' \
  -f 'required_status_checks[contexts][]=backend (discovery-server)' \
  -f 'required_status_checks[contexts][]=backend (authorization-server)' \
  -f 'required_status_checks[contexts][]=backend (user-service)' \
  -f 'required_status_checks[contexts][]=backend (gateway)' \
  -f 'required_status_checks[contexts][]=backend (notification-service)' \
  -f 'required_status_checks[contexts][]=frontend' \
  -f 'required_status_checks[contexts][]=compose-validate' \
  -f 'required_status_checks[contexts][]=smoke-test-login' \
  -F 'enforce_admins=false' \
  -F 'required_pull_request_reviews=null' \
  -F 'restrictions=null'
```

> Os nomes dos checks só existem no GitHub após o workflow rodar ao menos uma vez — aplique
> a regra depois da primeira run verde.

---

## Testes

Requerem Java 21 + Maven 3.9+ no host:

```bash
# user-service — unitários + controllers + integração
# (integração usa Testcontainers: requer Docker rodando)
cd user-service && mvn test

# authorization-server — unitários
cd authorization-server && mvn test
```
