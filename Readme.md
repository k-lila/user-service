# users

API REST de gerenciamento de usuários construída com arquitetura de microsserviços.

**Stack:** Java 21 · Spring Boot 4 · Spring Cloud 2025 · MongoDB · Redis · OAuth2 + PKCE · React 19

---

## Arquitetura

```
login-interface (React)
        │
        ▼
    gateway :8081      ← único ponto de entrada externo
    ├── /users/**        → user-service :8090
    ├── /oauth2/**       → authorization-server :8082
    └── /login           → authorization-server :8082
              │
              ├── config-server :8888    (Spring Cloud Config)
              ├── discovery-server :9091 (Eureka)
              ├── MongoDB                (persistência de usuários)
              ├── Redis                  (cache + rate limiting)
              └── Zipkin / Prometheus / Grafana (observabilidade)
```

---

## Pré-requisitos

| Ferramenta              | Versão mínima | Necessário para                      |
| ----------------------- | ------------- | ------------------------------------ |
| Docker + Docker Compose | 24+           | Execução via container (recomendado) |
| Java                    | 21            | Build/execução manual                |
| Maven                   | 3.9+          | Build/execução manual                |
| Node.js                 | 20+           | Front-end local                      |

---

## Execução com Docker (recomendado)

```bash
# Subir todos os serviços
docker compose up -d --build

# Acompanhar logs
docker compose logs -f

# Derrubar (incluindo volumes)
docker compose down -v
```

---

## Execução manual (sem Docker)

### 1. Infraestrutura

```bash
# Sobe apenas MongoDB e Redis
docker compose up -d user-mongo user-redis
```

### 2. Serviços Spring (um terminal por serviço)

```bash
# 1º — config-server
cd config-server && mvn spring-boot:run

# 2º — discovery-server
cd discovery-server && mvn spring-boot:run

# 3º — authorization-server
cd authorization-server && mvn spring-boot:run

# 4º — user-service
cd user-service && mvn spring-boot:run

# 5º — gateway
cd gateway && mvn spring-boot:run
```

### 3. Front-end (opcional)

```bash
cd login-interface
npm install
npm run dev
```

### Variáveis de ambiente

As variáveis abaixo são necessárias para execução manual fora do Docker. No Docker, já estão configuradas no `docker-compose.yml`.

| Variável                                  | Serviço               | Descrição                                      |
| ----------------------------------------- | --------------------- | ---------------------------------------------- |
| `CONFIG_SERVER_URL`                       | todos                 | URL do config-server                           |
| `EUREKA_URI`                              | todos                 | URL do Eureka (`http://localhost:9091/eureka`) |
| `MONGODB_URI`                             | user-service          | URI de conexão com o MongoDB                   |
| `MONGODB_DATABASE`                        | user-service          | Nome do banco (`user-db`)                      |
| `REDIS_HOST` / `REDIS_PORT`               | user-service, gateway | Host e porta do Redis                          |
| `AUTH_ISSUER_URI`                         | user-service          | URI do authorization-server (validação JWT)    |
| `OAUTH_CLIENT_ID` / `OAUTH_CLIENT_SECRET` | gateway               | Credenciais do cliente OAuth2                  |
| `VITE_API_URL`                            | login-interface       | URL base da API (`http://localhost:8081`)      |

---

## URLs de acesso

| Serviço       | URL                                         |
| ------------- | ------------------------------------------- |
| API (gateway) | http://localhost:8081                       |
| Swagger UI    | http://localhost:8081/swagger-ui/index.html |
| Front-end     | http://localhost:5173                       |
| Eureka        | http://localhost:9091                       |
| Zipkin        | http://localhost:9411                       |
| Prometheus    | http://localhost:9090                       |
| Grafana       | http://localhost:3000 (admin / admin)       |

---

## Testes

```bash
# Testes unitários
cd user-service && mvn test

# Testes de integração (requer Docker para subir MongoDB e Redis via Testcontainers)
cd user-service && mvn verify
```
