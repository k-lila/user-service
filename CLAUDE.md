# CLAUDE.md

## Restrições de Execução

- Não refatore código fora do escopo explícito da tarefa pedida
- Não adicione tratamento de erro para cenários impossíveis
- Não crie arquivos sem ser pedido explicitamente
- Pergunte antes de agir se a tarefa tiver mais de 3 arquivos envolvidos

---

## Visão Geral do Projeto

Sistema de microsserviços em Java + Spring para gerenciamento de usuários, pronto para produção. O objetivo central é fornecer uma base sólida de autenticação, registro e controle de acesso sobre a qual outras camadas de domínio serão adicionadas futuramente.

O front-end React existe **apenas como demonstração** do fluxo OAuth2/JWT. Ele está incompleto e incompatível com o fluxo OAuth2 atual — foi construído para uma autenticação simples (token direto via POST /login) e ainda não foi adaptado para o fluxo de autorização via código com PKCE.

---

## Arquitetura

```
login-interface (React)
        │
        ▼
    gateway :8081          ← único ponto de entrada externo
    ├── /users/register  → user-service
    ├── /oauth2/**       → authorization-server
    ├── /login           → authorization-server
    └── /users/**        → user-service
        │
        ├── authorization-server :8082
        │       └── chama user-service via Feign (endpoint interno)
        │
        ├── user-service :8090
        │       ├── MongoDB (persistência)
        │       └── Redis (cache + rate limiting)
        │
        ├── discovery-server :9091  (Eureka)
        ├── config-server :8888     (Spring Cloud Config)
        ├── zipkin :9411            (rastreamento distribuído)
        ├── prometheus :9090
        └── grafana :3000
```

**Tecnologias:**
- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.0, Maven
- MongoDB (dados de usuário), Redis (cache e rate limiting)
- React 19 + TypeScript + Vite + TailwindCSS 4 (front-end)
- Docker Compose para orquestração completa

---

## Serviços

### config-server (porta 8888)
- Gerencia configurações centralizadas via `classpath:/config`
- Todos os outros serviços importam configuração com `spring.config.import=optional:configserver:${CONFIG_SERVER_URL}`
- Arquivos de config: `config-server/src/main/resources/config/{nome-do-servico}.yml`
- **Deve ser o primeiro a subir.** Todos os demais dependem dele.

### discovery-server (porta 9091)
- Netflix Eureka — registra e descobre serviços
- Ele próprio **não** se registra no Eureka
- Deve subir logo após o config-server

### authorization-server (porta 8082)
- OAuth2 Authorization Server (Spring Security)
- Fluxo: authorization_code + PKCE + refresh_token
- Registra o cliente `gateway-client` em memória
- Busca credenciais do usuário chamando `user-service` via Feign: `GET /internal/users/email/{email}`
- Customiza o JWT com: `userID`, `roles`, `permissions` (`users.read`, `users.write`)
- Suporte a OIDC (escopos: `openid`, `profile`)
- Arquivo crítico: `TokenCustomizerConfig.java` — define o que vai no token

### user-service (porta 8090)
- Domínio central: CRUD de usuários
- Banco: MongoDB, coleção `users`
- Cache: Redis (TTL 5 min, caches: `"usersById"` e `"usersByEmail"`)
- Dois controllers:
  - `UserController` — endpoints públicos (via gateway)
  - `InternalUserController` — `GET /internal/users/email/{email}`, sem autenticação, **não exposto pelo gateway**, usado exclusivamente pelo authorization-server via Feign

**Endpoints expostos via gateway:**

| Método | Path | Auth | Rate Limit |
|--------|------|------|------------|
| POST | /users/register | Nenhuma | 2 req/s (IP) |
| GET | /users | ROLE_USER | 10 req/s (user) |
| GET | /users/{id} | ROLE_USER | 10 req/s (user) |
| GET | /users/email/{email} | ROLE_USER | 10 req/s (user) |
| GET | /users/me | ROLE_USER | 10 req/s (user) |
| PUT | /users | ROLE_USER | 10 req/s (user) |
| DELETE | /users/{id} | ROLE_ADMIN | 10 req/s (user) |
| DELETE | /users/del/{id} | ROLE_ADMIN | 10 req/s (user) |
| DELETE | /users/remove/{id} | ROLE_USER | 10 req/s (user) |

**Schema MongoDB:**
```js
{
  _id: ObjectId,
  name: String,       // 1–50 chars
  email: String,      // unique, formato e-mail
  passwordHash: String, // BCrypt
  registrationDate: ISODate,
  roles: [String],    // ex: ["USER"], ["USER", "ADMIN"]
  active: Boolean
}
```

**Estratégia de cache:**
- Dois caches Redis distintos: `usersById` (chave = ID) e `usersByEmail` (chave = e-mail)
- Leitura (declarativa): `@Cacheable("usersById")` em `searchById` e `@Cacheable("usersByEmail")` em `searchByEmail`
- Escrita (manual via `CacheManager`): `updateUser` atualiza `usersById` e evicta o e-mail antigo e o novo em `usersByEmail`; `deleteUser` e `deactivateUser` evictam ambos os caches. A escrita é manual (não declarativa) porque cada cache usa uma chave diferente — ID vs e-mail

### gateway (porta 8081)
- Spring Cloud Gateway (WebFlux/reativo)
- Único ponto de entrada externo — **nunca chame os serviços diretamente em produção**
- Rate limiting via Redis (token bucket):
  - LOW: 2 req/s, capacity 5 (registro, por IP)
  - MED: 5 req/s, capacity 10 (OAuth2, por IP)
  - HIGH: 10 req/s, capacity 20 (usuários autenticados, por user)
- Filtros: `CorrelationIdFilter`, `JwtHeaderPropagationFilter`, `TokenRelay`
- Load balancing via Eureka (`lb://nome-do-servico`)

### login-interface (porta 5173 dev / 80 Docker)
- React 19 + TypeScript + Vite + TailwindCSS 4
- **Estado atual: incompleto e incompatível com o fluxo OAuth2**
  - Foi construído para autenticação direta (POST /login com credenciais, recebendo token)
  - O authorization-server usa authorization_code com PKCE — o front-end precisa ser reescrito
- Token armazenado em `localStorage` (interceptor Axios adiciona `Authorization: Bearer`)
- Estrutura: `pages/`, `components/`, `hooks/`, `api/`

**Plano de reescrita — SPA redirect-based (authorization_code + PKCE):**
```
1. Usuário clica "Login" no front-end
2. Front-end redireciona para: GET /oauth2/authorize?response_type=code&client_id=...&code_challenge=...
3. authorization-server exibe form de login e autentica o usuário
4. authorization-server redireciona de volta ao front-end com ?code=...
5. Front-end faz POST /oauth2/token trocando o código pelo JWT
6. Token armazenado e usado nas requisições subsequentes
```

---

## Fluxo de Autenticação (OAuth2)

```
1. Usuário → GET /oauth2/authorize (gateway)
2. Gateway → redireciona para authorization-server
3. authorization-server → exibe form de login
4. Usuário → submete credenciais
5. authorization-server → chama user-service /internal/users/email/{email}
6. user-service → retorna dados do usuário (hash, roles)
7. authorization-server → valida senha (BCrypt), gera JWT com claims customizados
8. JWT → retornado ao gateway via redirect com código de autorização
9. Gateway → troca código por token (/oauth2/token)
10. Requests subsequentes → JWT propagado via TokenRelay para os serviços downstream
```

**Claims customizados no JWT:**
- `userID`: ID do usuário no MongoDB
- `roles`: lista de roles (ex: `["USER"]`)
- `permissions`: `["users.read", "users.write"]`

---

## Desenvolvimento Local

### Subir tudo com Docker
```bash
docker compose up -d --build
```

### Ordem manual de inicialização (sem Docker)
1. `config-server` — `mvn spring-boot:run`
2. `discovery-server` — `mvn spring-boot:run`
3. `authorization-server` — `mvn spring-boot:run`
4. `user-service` — `mvn spring-boot:run`
5. `gateway` — `mvn spring-boot:run`
6. `login-interface` — `npm run dev`

### Variáveis de ambiente relevantes
- `CONFIG_SERVER_URL` — URL do config-server
- `EUREKA_URI` — URL do Eureka
- `AUTH_ISSUER_URI` — URI do authorization-server (para validação JWT)
- `MONGODB_URI` / `MONGODB_DATABASE`
- `REDIS_HOST` / `REDIS_PORT`
- `OAUTH_CLIENT_ID` / `OAUTH_CLIENT_SECRET` (gateway)
- `VITE_API_URL` (front-end)

### URLs de acesso
| Serviço | URL |
|---------|-----|
| Gateway / API | http://localhost:8081 |
| Swagger UI | http://localhost:8081/swagger-ui/index.html |
| Eureka | http://localhost:9091 |
| Zipkin | http://localhost:9411 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| Front-end | http://localhost:5173 |

---

## Observabilidade

- **Zipkin**: rastreamento distribuído com B3 propagation, 100% sampling
- **Prometheus**: métricas expostas em `/actuator/prometheus` em todos os serviços; scrape a cada 5s
- **Grafana**: dashboards pré-provisionados, conectado ao Prometheus
- **SLOs configurados**: 50ms, 100ms, 200ms, 500ms, 1s, 2s

---

## Convenções e Decisões de Design

- **Separação de responsabilidades rígida**: authorization-server não acessa MongoDB diretamente — apenas via Feign para user-service
- **Endpoint interno isolado**: `/internal/users/email/{email}` não está registrado nas rotas do gateway e não aparece no Swagger — é canal exclusivo entre authorization-server e user-service
- **Endpoints DELETE com semânticas distintas e intencionais**:
  - `DELETE /users/{id}` (ADMIN) → soft-delete (`deactivateUser`, seta `active = false`)
  - `DELETE /users/del/{id}` (ADMIN) → hard-delete (`deleteUser`, remove do banco)
  - `DELETE /users/remove/{id}` (USER, auto-remoção) → soft-delete (`deactivateUser`)
- **BCrypt** para hash de senha — custo padrão (10)
- **Roles são fixas**: apenas `USER` e `ADMIN`. Não há sistema de roles dinâmicas — roles são strings simples no MongoDB: `["USER"]`, `["USER", "ADMIN"]`
- **Configuração centralizada**: segredos vêm do config-server via variáveis de ambiente. Segredos hardcoded são gaps de segurança conhecidos (ver seção abaixo), não padrão intencional

---

## Bugs Conhecidos

| Arquivo | Bug | Comportamento Atual | Comportamento Esperado |
|---------|-----|---------------------|------------------------|
| `user-service/.../dtos/UserRequestDTO.java` | Campo `passwordHash` nomeado incorretamente | Recebe senha em **plain text**; BCrypt é aplicado no servidor em `RegisterService` | Renomear para `password` para refletir o que realmente é recebido |

> **Corrigido:** `RegisterService.updateUser()` — a validação de e-mail estava invertida (lançava exceção quando os e-mails eram *diferentes*). Agora lança exceção apenas quando o novo e-mail já pertence a *outro* usuário existente.

> O campo no `domain/User.java` (`passwordHash`) está correto — armazena o resultado do BCrypt. Apenas o DTO de entrada precisa ser renomeado.

---

## Estratégia de Testes

**Estado atual:** 28 testes unitários + 18 testes de integração (Testcontainers), BUILD SUCCESS em ambos os módulos.

**Unitários (Mockito):**

| Serviço | Arquivo de teste | Testes |
|---------|-----------------|--------|
| `RegisterService` | `user-service/.../services/RegisterServiceTest.java` | 12 |
| `SearchService` | `user-service/.../services/SearchServiceTest.java` | 7 |
| `AuthenticationService` (user-service) | `user-service/.../services/AuthenticationServiceTest.java` | 3 |
| `AuthorizationService` (authorization-server) | `authorization-server/.../services/AuthorizationServiceTest.java` | 6 |

**Integração (Testcontainers — MongoDB `mongo:7` + Redis `redis:7-alpine`):**

| Foco | Arquivo de teste | Testes |
|------|-----------------|--------|
| Fluxo registro → busca → atualização → desativação/remoção e unicidade de e-mail | `user-service/.../integration/UserFlowIntegrationTest.java` | 10 |
| Comportamento do cache Redis (popular/evictar `usersById` e `usersByEmail`) | `user-service/.../integration/CacheIntegrationTest.java` | 8 |

> Base comum: `AbstractIntegrationTest` sobe os containers, mocka o `JwtDecoder` e limpa Redis (`flushDb`) + os caches `usersById`/`usersByEmail` entre os testes.

---

## Trabalho Pendente (Estado Atual)

**Correções de bugs (back-end):**
- [x] Corrigir `RegisterService.updateUser()` — lógica de validação de e-mail invertida
- [ ] Renomear `UserRequestDTO.passwordHash` → `password` (e ajustar usages)

**Qualidade:**
- [x] Implementar testes unitários dos services (Mockito) — 28 testes, BUILD SUCCESS
- [x] Implementar testes de integração (Testcontainers — MongoDB + Redis) — 18 testes
- [ ] Adicionar Resilience4j como circuit breaker na chamada Feign do authorization-server → user-service

**Front-end:**
- [ ] Reescrever `login-interface` como SPA redirect-based (authorization_code + PKCE)

**Segurança (pré-produção):**
- [ ] Externalizar `gateway-secret` de `OAuth2ClientConfig.java` para variável de ambiente
- [ ] Remover credenciais MongoDB dos fallbacks hardcoded nos `.yml` do config-server
- [ ] Restringir CORS de `withDefaults()` para origens explícitas em todos os serviços
- [ ] Mover MongoDB e Redis para rede privada (sem exposição de portas no host)

**Fluxos futuros (não imediatos):**
- [ ] Verificação de e-mail no cadastro
- [ ] Recuperação de senha

**Próximas camadas de domínio:**
- [ ] Outros microsserviços de negócio serão adicionados sobre esta base após o sistema de usuários estar estável

---

## Gaps de Segurança Conhecidos

Identificados e com decisão de abordagem registrada:

| Gap | Localização | Severidade | Decisão |
|-----|-------------|------------|---------|
| Segredo OAuth2 `gateway-secret` hardcoded | `authorization-server/.../config/OAuth2ClientConfig.java:37` e `docker-compose.yml` | Alta | Externalizar para env var antes de produção |
| Credenciais MongoDB como fallback hardcoded | `config-server/src/main/resources/config/user-service.yml` | Alta | Remover defaults; usar apenas env vars |
| CORS com wildcard (`withDefaults()`) | Todos os serviços (`CORSConfig.java`) | Média | Restringir para origens explícitas antes de produção |
| JWT armazenado em `localStorage` no front-end | `login-interface/src/hooks/useLogin.ts` | Média | Mantido por ora (SPA redirect-based). Avaliar cookies HttpOnly futuramente |
| MongoDB (27020) e Redis (6379) expostos no host | `docker-compose.yml` | Média | Mover para rede privada antes de produção |
| Grafana acessível com `admin/admin` | `docker-compose.yml` | Baixa | Alterar credenciais e proteger o stack de observabilidade |
| Sem HTTPS/TLS | Todo o sistema | Alta | Decidido: configurar junto com a infraestrutura de produção |
| `InMemoryRegisteredClientRepository` no authorization-server | `OAuth2ClientConfig.java` | Baixa | Aceitável enquanto houver apenas um cliente (gateway). Migrar para JDBC se necessário |

---

## Estrutura de Arquivos

```
/
├── authorization-server/
│   └── src/main/java/authorizationserver/
│       ├── config/          # SecurityConfig, OAuth2ClientConfig, TokenCustomizerConfig, JWKConfig, CORSConfig
│       ├── services/        # AuthorizationService (UserDetailsService)
│       ├── clients/         # IUserClient (Feign → user-service)
│       └── dtos/            # AuthDTO
├── user-service/
│   └── src/main/java/com/users/userservice/
│       ├── config/          # SecurityConfig, CacheConfig, MongoConfig, OpenAPIConfig, WebConfig, CORSConfig
│       ├── controller/      # UserController, InternalUserController
│       ├── services/        # RegisterService, SearchService, AuthenticationService
│       ├── domain/          # User.java (@Document MongoDB)
│       ├── repository/      # IUserRepository (MongoRepository)
│       ├── dtos/            # UserRequestDTO, UserResponseDTO, AuthDTO
│       └── exceptions/      # DomainEntityNotFound
├── gateway/
│   └── src/main/java/com/users/gateway/
│       ├── config/          # SecurityConfig, RateLimiterConfig, OpenAPIConfig, CORSConfig
│       ├── routing/         # GatewayRouter
│       └── filter/          # CorrelationIdFilter, JwtHeaderPropagationFilter
├── discovery-server/
├── config-server/
│   └── src/main/resources/config/  # *.yml por serviço
├── login-interface/
│   └── src/
│       ├── api/             # authClient.ts, userClient.ts, apiAxios.ts
│       ├── hooks/           # useLogin, useRegister, useCurrentUser
│       ├── components/      # LoginBox, RegisterBox, NavBar, ProfileBox, ProtectedLayout
│       ├── pages/           # Login, Register, Dashboard
│       └── routes/          # router.tsx
├── grafana/
├── docker-compose.yml
└── prometheus.yml
```
