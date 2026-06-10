# users

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
│   ├── config-lb/                #   nginx LB dos config-servers
│   ├── tls-proxy/                #   nginx de borda TLS (overlay opcional)
│   ├── mongo/                    #   keyfile do replica set
│   ├── redis/                    #   sentinel.conf
│   ├── grafana/                  #   dashboards e datasources provisionados
│   └── prometheus.yml            #   alvos de scrape
├── docs/                         # Documentação técnica detalhada
├── docker-compose.yml            # Base prod-safe (publica só a borda)
├── docker-compose.override.yml   # Deltas de dev (republica portas internas; auto-carregado)
├── docker-compose.tls.yml        # Overlay opcional de TLS na borda
└── .env.example                  # Template do .env (contrato de variáveis, comentado)
```

---

## Pré-requisitos

| Ferramenta              | Versão mínima | Necessário para            |
| ----------------------- | ------------- | -------------------------- |
| Docker + Docker Compose | 24+           | Execução (única suportada) |
| mkcert                  | —             | Apenas para o modo TLS     |

---

## Execução

### 1. Criar o `.env` (obrigatório)

O compose não tem defaults de segredo — sem `.env` a subida falha de propósito (fail-fast):

```bash
cp .env.example .env   # e preencha os valores (em dev, qualquer valor consistente serve)
```

### 2a. Sem TLS (HTTP, modo padrão de desenvolvimento)

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

### 2b. Com TLS (HTTPS na borda, mesma topologia da produção)

Um reverse-proxy nginx termina TLS com certificado do mkcert e mantém o tráfego interno em HTTP. Setup único do certificado:

```bash
# 1. Instalar o mkcert (Debian/Ubuntu; binários em github.com/FiloSottile/mkcert/releases)
sudo apt install libnss3-tools

# 2. Instalar a CA local no trust store do SO/navegador
mkcert -install

# 3. Emitir o cert da borda, na raiz do repositório
mkcert -cert-file infra/tls-proxy/certs/edge.crt \
       -key-file  infra/tls-proxy/certs/edge.key \
       app.localhost auth.localhost localhost 127.0.0.1
```

Subida com o overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
```

Acesso: **https://app.localhost** (SPA + API) · **https://auth.localhost** (front-channel OAuth2). Os cookies de sessão saem com a flag `Secure`. Ir para produção real = trocar o cert do mkcert por ACME/corporativo e os hostnames `*.localhost` por domínios reais — topologia e código não mudam.

---

## URLs de acesso (dev)

| Serviço       | URL                                           |
| ------------- | --------------------------------------------- |
| API (gateway) | http://localhost:8081                         |
| Swagger UI    | http://localhost:8081/swagger-ui/index.html   |
| Front-end     | http://localhost:5173                         |
| Eureka        | http://localhost:9091 · http://localhost:9092 |
| Zipkin        | http://localhost:9411                         |
| Prometheus    | http://localhost:9090                         |
| Grafana       | http://localhost:3000 (credenciais do `.env`) |

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
