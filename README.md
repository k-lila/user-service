# user-service

[![CI](https://github.com/k-lila/user-service/actions/workflows/ci.yml/badge.svg)](https://github.com/k-lila/user-service/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)
![Cobertura](https://img.shields.io/badge/cobertura-96%25%2B-brightgreen)

Sistema de identidade e gerenciamento de usuários em microsserviços: registro, login, controle de
sessão, autenticação e autorização.

O projeto nasceu como um CRUD (Create, Read, Update, Delete) de usuários em arquitetura de
microsserviços e virou um projeto de estudo de alguns dos problemas próprios dessa arquitetura:
descoberta de serviços, configuração centralizada, resiliência entre chamadas, rastreamento de
requisições que atravessam vários serviços e escala independente de cada parte.

Ele também marca o início do meu uso de IA na geração de código, a partir de duas fases distintas.
Na primeira, trabalhei através de prompts diretos no Claude Code, que tem acesso ao código e ao
contexto da aplicação. Na segunda, trabalhei com um time de agentes especializados, cada um com seu
papel: produto, liderança técnica, QA (Quality Assurance), segurança, dependências, revisão crítica e
relatório.

## Demonstrações

Fluxo de login -> Swagger-UI

https://github.com/user-attachments/assets/4fc78b99-18c2-49ad-ac7c-8c2349b6665c

Tracing distribuído com Zipkin

https://github.com/user-attachments/assets/a50240ac-c3e2-4bd1-84da-9f38293fe073

Dashboards com Grafana e Prometheus

https://github.com/user-attachments/assets/0e8daf21-7fc9-4119-9732-640cbd3de043

## Diretrizes gerais

### Separação de responsabilidades

Cada responsabilidade tem um serviço dedicado. É o ponto central do projeto e um de seus maiores
desafios, pois é necessário que os serviços trabalhem em conjunto para que a aplicação funcione
corretamente.

| Serviço                | Responsabilidade                          |
| ---------------------- | ----------------------------------------- |
| `gateway`              | Ponto de entrada único                    |
| `config-server`        | Configuração centralizada dos serviços    |
| `user-service`         | Domínio de usuários e trilha de auditoria |
| `authorization-server` | Autenticação/autorização                  |
| `notification-service` | Envio de e-mail por SMTP                  |
| `discovery-server`     | Service discovery com Eureka              |
| `login-interface`      | SPA (Single Page Application) em React    |

### Observabilidade

- **Tracing distribuído (Zipkin):** propagação B3 entre os serviços. O `traceId` e o `spanId`
  aparecem em todo log, e uma requisição pode ser seguida de ponta a ponta, inclusive nas
  chamadas Feign.
- **Métricas (Prometheus):** coleta as métricas dos serviços cada 5 segundos pelo `/actuator/prometheus`,
  e fornece dados para dashboards, por exemplo.
- **Dashboards (Grafana):** cinco painéis pré-provisionados (HTTP, JVM, MongoDB, PostgreSQL e
  Redis).
- **Logs estruturados**, com mascaramento de dado pessoal.

### Segurança

- **OAuth2 com OIDC:** fluxo `authorization_code` com PKCE (Proof Key for Code Exchange)
  obrigatório e refresh token.
- **Padrão BFF:** o gateway é o cliente OAuth2, e a SPA usa sessão por cookie `HttpOnly`, sem
  `localStorage`. O token de acesso nunca chega ao navegador.
- **Revogação ativa de token**, bloqueio de conta contra força bruta, verificação de e-mail no
  cadastro e rate limiting em três níveis.
- **LGPD (Lei Geral de Proteção de Dados):** consentimento no cadastro, trilha de auditoria e
  leitura de dado pessoal de terceiros restrita ao papel ADMIN.

### Escalabilidade

Os serviços escalam individualmente, e a redundância dos dados sobe por perfil:

- **Piso mínimo:** sobe apenas um membro do replica set do MongoDB, um Redis e um
  Sentinel, assim como uma instância de cada serviço.
- **Redundância:** acrescenta os nós de alta disponibilidade (MongoDB, Redis,
  Sentinel e um segundo Eureka).
- **Réplicas:** adiciona mais instâncias dos serviços.

A escalabilidade, contudo, é restrita a uma mesma máquina.

---

## Execução

As informações detalhadas das configurações, secrets, etc., e de como preenchê-las, são encontradas
em [docs/RECEITA.md](docs/RECEITA.md).

Configurado, o projeto pode ser manejado com docker compose e alguns comandos básicos:

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
