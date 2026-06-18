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
    ├── /users/**        → user-service :8090
    ├── /oauth2/**       → authorization-server :8082
    └── /login           → authorization-server :8082
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
├── login-interface/              # SPA React (Vite + TailwindCSS)
├── infra/                        # Configs de infraestrutura:
│   ├── secrets/                  #   gen-secrets.sh (gera os Docker secrets)
│   ├── jwk/                      #   gen-keys.sh (par de chaves JWT)
│   ├── config-lb/                #   nginx LB dos config-servers
│   ├── mongo/                    #   keyfile do replica set
│   ├── redis/                    #   sentinel.conf
│   ├── grafana/                  #   dashboards e datasources provisionados
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

O `.env` guarda apenas as **identidades não-segredo** (usuários, hostnames públicos) interpoladas no compose — os segredos vêm do passo 0. Sem `.env` a subida falha de propósito (fail-fast):

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

### 2b. Deploy na própria máquina via Cloudflare Tunnel

```bash
# 1. Boot com placeholder (satisfaz o fail-fast das envs ${TUNNEL_ORIGIN:?})
export TUNNEL_ORIGIN=https://placeholder.trycloudflare.com
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --build

# 2. Ler a URL pública REAL nos logs do cloudflared
docker compose -f docker-compose.yml -f docker-compose.deploy.yml logs cloudflared

# 3. Re-subir SÓ os serviços de app com --no-deps (não recria o cloudflared → URL estável)
export TUNNEL_ORIGIN=https://<sub>.trycloudflare.com
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --no-deps \
  gateway authorization-server user-service
```

---

## URLs de acesso (dev)

| Serviço       | URL                                                                              |
| ------------- | -------------------------------------------------------------------------------- |
| API (gateway) | http://localhost:8081                                                            |
| Swagger UI    | http://localhost:8081/swagger-ui/index.html                                      |
| Front-end     | http://localhost:5173                                                            |
| Eureka        | http://localhost:9091 · http://localhost:9092                                    |
| Zipkin        | http://localhost:9411                                                            |
| Prometheus    | http://localhost:9090                                                            |
| Grafana       | http://localhost:3000 (user do `.env`, senha do secret `GRAFANA_ADMIN_PASSWORD`) |

---

## Integração Contínua (CI)

A cada `push` na `main` e a cada `pull_request`, o workflow [`ci.yml`](.github/workflows/ci.yml)
roda no GitHub Actions três frentes em paralelo:

| Job                | O que roda                                                                                                                      |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| `backend` (matrix) | `mvn -B verify` por módulo (5 serviços) — dispara o gate de cobertura JaCoCo; integração via Testcontainers no Docker do runner |
| `frontend`         | `npm ci` + `npm run coverage` no `login-interface` — Vitest com threshold de 80%                                                |
| `compose-validate` | `docker compose -f docker-compose.yml config -q` — valida a topologia base                                                      |

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
  -f 'required_status_checks[contexts][]=frontend' \
  -f 'required_status_checks[contexts][]=compose-validate' \
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
