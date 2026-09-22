# user-service

[![CI](https://github.com/k-lila/user-service/actions/workflows/ci.yml/badge.svg)](https://github.com/k-lila/user-service/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)
![Cobertura](https://img.shields.io/badge/cobertura-96%25%2B-brightgreen)

Este projeto... (descrição incompleta)

<!--
  ESPAÇO DO GIF — grave o fluxo completo e salve em docs/assets/login.gif, depois descomente:
  roteiro sugerido (~20s): cadastro → login no formulário do IdP → dashboard com dados do
  titular → logout voltando à tela inicial.

  ![Fluxo de login](docs/assets/login.gif)
-->

---

## Execução

| Ferramenta              | Versão mínima | Necessário para            |
| ----------------------- | ------------- | -------------------------- |
| Docker + Docker Compose | 24+           | Execução (única suportada) |

As informações detalhadas das configurações, secrets, etc., e de como preenchê-las, são encontradas
em [docs/RECEITA.md](docs/RECEITA.md).

Configurado, o projeto pode ser manejado com alguns comandos básicos:

### A) Ambiente local

```bash
docker compose up -d --build
docker compose down -v
```

### B) Deploy com Cloudflare Tunnel

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.deploy.yml down
```

---

## URLs de acesso (dev)

| Serviço              | URL                                                                                 |
| -------------------- | ----------------------------------------------------------------------------------- |
| API (gateway)        | http://localhost:8081                                                               |
| Swagger UI           | http://localhost:8081/swagger-ui/index.html                                         |
| Front-end            | http://localhost:5173                                                               |
| authorization-server | http://localhost:8082                                                               |
| user-service         | http://localhost:8090                                                               |
| notification-service | http://localhost:8095                                                               |
| config-lb            | http://localhost:8888                                                               |
| Eureka               | http://localhost:9091 · http://localhost:9092                                       |
| Zipkin               | http://localhost:9411 🔒                                                            |
| Prometheus           | http://localhost:9090 🔒                                                            |
| Grafana              | http://localhost:3000 🔒 (user do `.env`, senha do secret `GRAFANA_ADMIN_PASSWORD`) |

---

## Testes

Requerem Java 21 + Maven 3.9+ no host:

```bash
# todos os testes - a partir da raiz, digite:
mvn test

```
