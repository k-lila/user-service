# Estratégia de Testes

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Diretrizes de testes](#diretrizes-de-testes)
- [Como executar](#como-executar)
- [Inventário atual](#inventário-atual)
- [Armadilhas e comportamentos não óbvios do stack](#armadilhas-e-comportamentos-não-óbvios-do-stack)
- [Lacunas e cobertura planejada](#lacunas-e-cobertura-planejada)

---

## Diretrizes de testes

> Diretrizes para análise e implementação de testes neste projeto. Aplicar sempre que um teste for criado, revisado ou avaliado.

### Princípio geral

Testes unitários e de integração respondem perguntas diferentes e são complementares — nenhum substitui o outro.

- **Unitário:** "a lógica desta unidade está correta?"
- **Integração:** "estas peças se comunicam corretamente com a infraestrutura real?"

---

### Diretrizes para testes unitários

**Escopo**

- Testar uma única unidade de comportamento por vez: uma classe de serviço, uma função de transformação/validação, um handler de evento.
- Não testar fluxos completos (controller + service + repositório) — isso é integração.
- Não testar getters/setters triviais, construtores sem lógica, código gerado (Lombok, MapStruct) ou frameworks externos.

**Isolamento**

- Substituir toda infraestrutura externa por doubles: repositórios, clientes Feign, relógio (`Clock`), filas.
- Nunca mockar a lógica que está sendo testada — o mock aprova código errado e a falha vai para produção.
- Usar `@ExtendWith(MockitoExtension.class)` com `@Mock` e `@InjectMocks`; evitar `@SpringBootTest` em unitários.

**Estrutura e nomenclatura**

- Seguir o padrão AAA: `// ARRANGE`, `// ACT`, `// ASSERT` — cada seção com uma responsabilidade.
- Nomear o teste com a intenção: `deveLancarExcecaoQuandoEmailJaExiste()`, não `test1()` ou `testCreate()`.
- Um teste, uma razão para falhar: se o teste cobre dois comportamentos distintos, dividir em dois.
- Não colocar lógica condicional (`if`, `for`) dentro do teste; usar `@ParameterizedTest` para múltiplos casos.

**O que cobrir**

- Toda lógica condicional (branches `if/else`, `switch`).
- Edge cases: `null`, vazio, valor mínimo, valor máximo, string em branco.
- Comportamento ao receber entrada inválida (exceção esperada, mensagem correta).
- Cada caminho relevante do código — cobertura de branch, não apenas de linha.

**Qualidade da asserção**

- Preferir `assertThat(...).isEqualTo(...)` (AssertJ) a `assertEquals` simples — mensagens de falha mais legíveis.
- Nunca escrever um teste sem `assert`; testes sem asserção passam sempre e não verificam nada.
- Não verificar interações (`verify`) quando a asserção sobre o resultado já é suficiente — `verify` é reservado para side-effects sem retorno observável.

**Sinal de design**

- Se a classe é difícil de instanciar no teste, tem responsabilidades demais (viola SRP).
- Se o teste precisa de muitos mocks para um comportamento simples, o acoplamento está alto.
- Se é impossível testar sem subir o contexto Spring, a lógica de negócio está misturada com infraestrutura.

---

### Diretrizes para testes de integração

**Escopo**

- Testar o contrato entre camadas que envolvem infraestrutura real: controller → service → repositório → banco; service → cache Redis.
- Não testar lógica de negócio pura em integração — isso é mais barato no unitário.
- Não testar todos os edge cases de validação em integração — cobrir apenas os que envolvem constraint de banco (unique, not-null).

**Infraestrutura**

- Usar Testcontainers (`mongo:7`, `redis:7-alpine`) — nunca banco compartilhado ou H2 como substituto de MongoDB.
- Seguir o padrão estabelecido: estender `AbstractIntegrationTest`, que já sobe os containers, mocka o `JwtDecoder` e limpa Redis + caches entre testes.
- Nunca assumir estado de um teste anterior — o `@BeforeEach` limpa o banco (`deleteAll()`) e o Redis (`flushDb()`).

**Estrutura e nomenclatura**

- Seguir AAA com dados reais: `ARRANGE` persiste no banco real; `ACT` chama o endpoint ou o serviço; `ASSERT` verifica o estado resultante.
- Nomear pelo comportamento do sistema: `deveEncontrarUsuarioPorIdAposRegistro()`, não `mongoSaveAndFindById()`.
- Não abstrair helpers que escondam o estado sendo preparado — o leitor deve entender o cenário sem navegar para outro arquivo.

**O que cobrir**

- Persistência: salvar e recuperar preserva todos os campos (sem divergência de nome entre objeto e documento).
- Queries: `findByEmail` retorna o documento correto; query em campo indexado usa o índice.
- Cache: miss na primeira chamada → `@Cacheable` popula → hit na segunda; evicção remove a entrada.
- Constraints de banco: email duplicado lança `DuplicateKeyException` (não silencia o erro).
- Contrato do endpoint: status HTTP correto, corpo JSON com campos esperados, headers de resposta.
- Segurança do endpoint: sem token → 401; token sem role adequada → 403; `X-Internal-Token` ausente/errado → 403.

**Velocidade**

- Reutilizar containers entre testes da mesma classe (campos `static @Container`).
- Preferir `@DataMongoTest` ou `@WebMvcTest` quando o teste não precisa do contexto completo — `@SpringBootTest` apenas quando necessário.
- Separar a execução: unitários a cada mudança; integração antes do commit e no CI.

---

## Como executar

Os testes de integração sobem containers Docker via Testcontainers — Docker deve estar disponível na máquina. Os testes rápidos (unitários e de controller) não têm essa dependência.

> **Não há pom agregador na raiz do repositório** — `mvn test` na raiz e `mvn test -pl <módulo>` **não funcionam**. Use `mvn -f <módulo>/pom.xml test` (ou rode `mvn test` dentro do diretório do módulo).

```bash
# user-service — suite completa (unitários + controller + integração)
mvn -f user-service/pom.xml test

# user-service — apenas testes rápidos, sem Docker (unitários + controller)
mvn -f user-service/pom.xml test -Dtest="!*IntegrationTest"

# user-service — apenas integração
mvn -f user-service/pom.xml test -Dtest="*IntegrationTest"

# authorization-server — suite completa (unitários + integração)
mvn -f authorization-server/pom.xml test

# authorization-server — apenas integração (OAuth2, lockout, circuit breaker, seed, sessão; Postgres+Redis+WireMock)
mvn -f authorization-server/pom.xml test -Dtest="*IntegrationTest"

# Classe específica
mvn -f user-service/pom.xml test -Dtest=UserControllerTest
```

**Quando rodar cada categoria:**

| Momento | O que rodar |
| ------- | ----------- |
| Durante desenvolvimento | `!*IntegrationTest` — feedback em segundos |
| Antes do commit | Suite completa do módulo alterado |
| CI (pull request) | Suite completa de cada módulo (`mvn -f <módulo>/pom.xml test`) |

---

## Inventário atual

**Total: 140 testes — BUILD SUCCESS em ambos os módulos.**

### Unitários (Mockito) — 45 testes

| Módulo | Classe de serviço | Arquivo de teste | Testes |
| ------ | ----------------- | ---------------- | ------ |
| `user-service` | `RegisterService` | `services/RegisterServiceTest.java` | 16 |
| `user-service` | `SearchService` | `services/SearchServiceTest.java` | 7 |
| `user-service` | `AuthenticationService` | `services/AuthenticationServiceTest.java` | 3 |
| `authorization-server` | `AuthorizationService` | `services/AuthorizationServiceTest.java` | 6 |
| `authorization-server` | `UserClientFallbackFactory` | `clients/UserClientFallbackFactoryTest.java` | 6 |
| `authorization-server` | `LoginAttemptService` | `services/LoginAttemptServiceTest.java` | 7 |

### Controller (`@WebMvcTest`) — 49 testes

MockMvc + `SecurityMockMvcRequestPostProcessors.jwt()`. Cobre status HTTP, autorização por role, extração de claims JWT e validação via Bean Validation.

| Módulo | Foco | Arquivo de teste | Testes |
| ------ | ---- | ---------------- | ------ |
| `user-service` | Endpoints públicos (`ROLE_USER` / `ROLE_ADMIN` / sem token) | `controller/UserControllerTest.java` | 45 |
| `user-service` | Endpoint interno `/internal/users/email/{email}` (200/404 com `X-Internal-Token`, 403 sem/errado) | `controller/InternalUserControllerTest.java` | 4 |

### Integração (Testcontainers) — 46 testes

Bases comuns por módulo: `AbstractIntegrationTest` no `user-service` (MongoDB `mongo:7` + Redis `redis:7-alpine`) e `AbstractAuthIntegrationTest` no `authorization-server` (PostgreSQL `postgres:16-alpine` + Redis `redis:7-alpine` + WireMock standalone como dublê do user-service; `flushDb()`, `resetAll()` e reset do `CircuitBreakerRegistry` entre testes).

| Módulo | Foco | Arquivo de teste | Testes |
| ------ | ---- | ---------------- | ------ |
| `user-service` | Fluxo registro → busca → atualização → desativação/remoção; unicidade de e-mail; índice único no banco | `integration/UserFlowIntegrationTest.java` | 18 |
| `user-service` | Comportamento do cache Redis: popular/evictar `usersById` e `usersByEmail` | `integration/CacheIntegrationTest.java` | 14 |
| `authorization-server` | Fluxo authorization_code + PKCE ponta a ponta: feliz → JWT com claims `userID`/`roles`/`permissions` reais (`TokenCustomizerConfig`/`JWKConfig`/seed do `gateway-client`); credenciais inválidas → `302 /login?error` sem code; sem PKCE → `error=invalid_request` | `integration/OAuth2AuthorizationCodeFlowIntegrationTest.java` | 3 |
| `authorization-server` | Lockout C19 e2e: 5 falhas → 6ª com senha correta bloqueada (redirect idêntico ao de senha errada, sem vazar distinção); sucesso antes do limite remove a chave `login_fail:*`; contas e IPs distintos não se contaminam (IP via `request.setRemoteAddr`) | `integration/LoginLockoutIntegrationTest.java` | 4 |
| `authorization-server` | Circuit breaker C7: downstream 500 → `/login?error` com exatamente 1 chamada Feign (guarda o C14); timeout do TimeLimiter → falha rápida; circuito aberto → fallback direto sem nova chamada, resposta < timeout | `integration/UserServiceCircuitBreakerIntegrationTest.java` | 3 |
| `authorization-server` | Seed idempotente do `gateway-client`: re-seed não duplica o client; `redirectUris` (5) e scopes (4) intactos no Postgres | `integration/RegisteredClientSeedIntegrationTest.java` | 2 |
| `authorization-server` | Sessão Redis (Spring Session): cookie `AUTHSESSION` → chave `spring:session:sessions:<id>` no Redis; saved request sobrevive na sessão | `integration/RedisSessionIntegrationTest.java` | 2 |

---

## Armadilhas e comportamentos não óbvios do stack

### Visibilidade eventual do `RedisCache`

Neste stack (Spring Boot 4.0.1 / spring-data-redis 4.0.1 / Lettuce 6.8.1, sem `commons-pool2`):

- **Causa:** `cache.put(...)` fica visível para um `cache.get(...)` da mesma chave com atraso de ~1–3 ms.
- **Consequência no teste:** os testes de cache **não fazem read-after-write direto**.
  - Leituras declarativas (`@Cacheable`): chamar o método de busca **duas vezes** — a 1ª dispara o `put`, a 2ª lê já do cache visível.
  - Asserções sobre `put`s manuais via `CacheService`: usar `await().atMost(...).untilAsserted(...)` (Awaitility, escopo `test`).
- **Por que produção está OK:** em produção a leitura vem em requisição HTTP posterior; o atraso de milissegundos é irrelevante.

### `@WebMvcTest` não carrega beans de segurança automaticamente no Spring Boot 4.0

O slice `@WebMvcTest` não inclui `SecurityConfig` nem `GlobalExceptionHandler` por auto-configuração. Sem `@Import` explícito, os testes de autorização passam em contexto sem segurança e os testes de erro retornam respostas inesperadas.

- **Fix:** anotar a classe de teste com `@Import({SecurityConfig.class, GlobalExceptionHandler.class})`.
- **Afeta:** todos os `@WebMvcTest` do `user-service`.

### Feign aponta para o WireMock via `SimpleDiscoveryClient` na integração do auth-server

O `IUserClient` resolve `user-service` por service discovery. Nos testes de integração (Eureka desabilitado), a instância é registrada no `SimpleDiscoveryClient` via propriedade dinâmica `spring.cloud.discovery.client.simple.instances.user-service[0].uri` apontando para o WireMock (`@DynamicPropertySource` em `AbstractAuthIntegrationTest`). Sem isso, o Feign falha na resolução do load balancer (não é um 404 do stub) e o login quebra de forma pouco óbvia.

### Estado do circuit breaker é compartilhado entre testes de integração

O estado do circuit breaker (C7) vive no `CircuitBreakerRegistry` do contexto Spring, que é reusado entre as classes de teste — um circuito aberto por um teste de falha contaminaria os seguintes (fallback direto sem chamada Feign). Fix: `AbstractAuthIntegrationTest` reseta o `CircuitBreakerRegistry` entre testes.

### Resilience4j com Feign: `configs.*` vale, `instances.*` não

Com Feign + Spring Cloud CircuitBreaker (group por nome do client), a resolução de configuração só enxerga `resilience4j.*.configs.*` — blocos `instances.user-service` apenas pré-criam um circuit breaker avulso que o Feign **não usa** (o id real é derivado do método, ex.: `IUserClientgetUserByEmailString`). Por isso o `application.yml` de teste do auth-server usa `configs.user-service`. O yml de **produção** ainda usa `instances.*` — risco registrado como **C20** em [TRABALHO_PENDENTE.md](TRABALHO_PENDENTE.md#4-eficiência-e-operação).

---

## Lacunas e cobertura planejada

Mapeamento dos caminhos (felizes, de erro e de borda) ainda **sem teste**, levantado por varredura do código de produção de todos os módulos cruzada com o inventário atual. Caminhos já cobertos não aparecem aqui.

> **Roadmap de implementação:** as fases de execução (C10.1–C10.6, com infra, dependências e critério de pronto) estão em [TRABALHO_PENDENTE.md § 3](TRABALHO_PENDENTE.md#3-qualidade-e-api).

### Visão geral por módulo

| Módulo | Cobertura atual | Lacuna dominante | Prioridade |
| ------ | --------------- | ---------------- | ---------- |
| `authorization-server` | 19 unitários + 14 integração (fluxo OAuth2 + lockout + circuit breaker + seed + sessão) | Unitários pontuais do C10.4 (`TokenCustomizerConfig` sem unitário) | **2** |
| `gateway` | só `contextLoads` | Tudo: rotas, rate limiting, CSRF/401, TokenRelay, filtros | **1** |
| `user-service` | 94 testes (robusto) | Pontuais: `CacheService`, handlers 400/500, `LogUtils` | **3** |
| `login-interface` | zero (sem test runner) | Componentes e fluxos BFF (C10) | **4** |
| `config-server` | só `contextLoads` | HTTP Basic (C17) e config servida | **5** |
| `discovery-server` | só `contextLoads` | — fora de escopo (ver nota final) | — |

---

### Testes unitários faltantes

#### `user-service`

**`CacheService`** (`services/CacheService.java`) — Mockito com mock de `CacheManager`/`Cache`. Hoje coberto só indiretamente (`verify` no `RegisterServiceTest` e integração).

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| `putById` / `putByEmail` com cache presente | feliz | delega `cache.put(key, dto)` |
| `evictById` / `evictByEmail` / `evictByEmailAuth` com cache presente | feliz | delega `cache.evict(key)` |
| `cacheManager.getCache()` retorna `null` (em qualquer dos 5 métodos) | borda | não lança — null-safe, apenas loga |

**`GlobalExceptionHandler`** (`exceptions/GlobalExceptionHandler.java`) — os handlers de 404/409/400-validação já são exercitados pelos testes de controller; faltam dois:

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| `IllegalArgumentException` | erro | 400 com `ProblemDetail` contendo a mensagem |
| `Exception` genérica | erro | 500 com detail fixo "Erro interno" — **não vaza** a mensagem interna |

Pode ser unitário direto ou `@WebMvcTest` forçando a exceção no service mockado.

**`InternalTokenFilter`** (`config/InternalTokenFilter.java`) — hoje coberto só indiretamente pelos 403 do `InternalUserControllerTest`:

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| rota fora de `/internal` | borda | `shouldNotFilter` → filtro não interfere |
| header `X-Internal-Token` ausente | erro | 403 com `ProblemDetail` |
| header presente mas errado | erro | 403 (comparação timing-constant via `MessageDigest.isEqual`) |

**`LogUtils.maskEmail`** (`utils/LogUtils.java`):

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| email válido | feliz | `fulano@email.com` → `f***@email.com` |
| `null` / blank | borda | não lança, retorna valor seguro |
| string sem `@` | borda | não lança, mascara de forma segura |

#### `authorization-server`

**`TokenCustomizerConfig`** (`config/TokenCustomizerConfig.java`) — **arquivo crítico sem nenhum teste**; é a peça que injeta os claims que o user-service usa para autorização:

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| access_token com authorities `USER_ID:` + `ROLE_USER` | feliz | claims `userID`, `roles=["USER"]`, `permissions=["users.read","users.write"]` |
| access_token com `ROLE_ADMIN` | feliz | `permissions` inclui o conjunto de admin (distinto do USER) |
| token que não é access_token (ex. id_token) | borda | não customiza nada |
| sem authority `USER_ID:` | borda | claim `userID` ausente/null, sem lançar |
| roles vazias | borda | `roles`/`permissions` vazios, sem lançar |

**`AuthorizationService`** (`services/AuthorizationService.java`) — branch do lockout C19 descoberto (o teste atual mocka `isBlocked=false` em todos os casos):

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| `loginAttempts.isBlocked()` retorna `true` | erro | `UserDetails.isAccountNonLocked() == false` → `LockedException` antes da checagem de senha |

**`LoginAttemptListener`** (`listeners/LoginAttemptListener.java`):

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| failure com `UsernamePasswordAuthenticationToken` (form login) | feliz | chama `recordFailure(email, ip)` |
| failure com outro tipo de token (client auth OAuth2) | borda | ignorado — **não** conta falha |
| success com `UsernamePasswordAuthenticationToken` | feliz | chama `loginSucceeded(email, ip)` |
| success com outro tipo de token | borda | ignorado |

**`ClientIpResolver`** (`utils/ClientIpResolver.java`):

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| dentro de request HTTP | feliz | retorna `request.getRemoteAddr()` |
| fora de contexto de request | borda | retorna `"unknown"` |
| `remoteAddr` null | borda | retorna `"unknown"` |

#### `gateway` (unitários reativos, sem contexto Spring)

**`CorrelationIdFilter`** (`filter/CorrelationIdFilter.java`):

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| header `X-Correlation-ID` presente | feliz | propagado inalterado downstream |
| header ausente | feliz | UUID gerado e injetado no request mutado |

**`RateLimitLogFilter`** (`filter/RateLimitLogFilter.java`):

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| response status 429 | feliz | log WARN com método, path e IP |
| status ≠ 429 | borda | silêncio (não loga) |
| `remoteAddress` *unresolved* (pós `X-Forwarded-For` consumido pelo framework) | borda | usa `getHostString()` — **sem NPE** (regressão documentada no CLAUDE.md) |

**`RateLimiterConfig` — key resolvers** (`config/RateLimiterConfig.java`):

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| `ipKeyResolver` com endereço normal | feliz | retorna o IP |
| `ipKeyResolver` com `InetSocketAddress` unresolved | borda | `getHostString()` sem NPE |
| `ipKeyResolver` com `remoteAddress` null | borda | fallback `"unknown"` |
| `userKeyResolver` com principal autenticado | feliz | nome do principal |
| `userKeyResolver` sem principal | borda | fallback (anônimo) |

---

### Testes de integração faltantes

#### `authorization-server`

**Integração entregue (C10.1 + C10.2)** — `AbstractAuthIntegrationTest` (Testcontainers `postgres:16-alpine` + `redis:7-alpine`, WireMock como dublê do user-service; o módulo não acessa MongoDB — só Feign) cobre o fluxo authorization_code + PKCE (C10.1) e as 4 frentes do C10.2 — lockout C19 e2e, circuit breaker C7, seed idempotente do `gateway-client` e sessão Redis — ver [Inventário atual](#inventário-atual). Sem lacuna de integração; restam os unitários pontuais do C10.4 (seção acima).

#### `gateway`

Sem nenhum teste funcional hoje. Infra: `WebTestClient` + Testcontainers `redis` + WireMock como downstream.

| Fluxo | Caminhos |
| ----- | -------- |
| **Roteamento** | `/v1/users/register` → user-service; `/oauth2/**` e `/login` → auth-server; rota autenticada `/v1/users/**` com **TokenRelay** → header `Authorization: Bearer` chega ao downstream |
| **Rate limiting** | estouro do bucket LOW (2 req/s cap 5) → 429; buckets independentes por IP (IPs distintos não compartilham); HIGH chaveado por usuário autenticado; rejeição 429 logada pelo `RateLimitLogFilter` |
| **Segurança BFF** | rota protegida sem sessão → **401, não 302**; `POST` sem `X-XSRF-TOKEN` → 403; `/v1/users/register` isento de CSRF; cookie `XSRF-TOKEN` emitido na resposta |
| **Correlation ID** | `X-Correlation-ID` recebido é propagado ao downstream; ausente é gerado |
| **Logout RP-initiated** | `POST /logout` → redirect ao `end_session_endpoint` com `id_token_hint` |

#### `config-server`

`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`, sem containers.

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| `GET /{app}/{profile}` sem credenciais | erro | 401 (HTTP Basic C17/G3) |
| `GET /{app}/{profile}` com Basic correto | feliz | 200 com a config do classpath |
| `GET /actuator/health` sem credenciais | feliz | 200 (aberto para healthchecks) |

#### `user-service` — menor prioridade (módulo já robusto)

| Caminho | Tipo | Comportamento esperado |
| ------- | ---- | ---------------------- |
| `/internal/users/email/{email}` com contexto completo (filtro real + Mongo) | feliz/erro | 200 com `X-Internal-Token` correto; 403 sem/errado — hoje só em slice `@WebMvcTest`, sem o stack de segurança completo |

#### `login-interface` — C10 (Vitest + React Testing Library + MSW)

Não há `vitest`, React Testing Library nem script `test` no `package.json` — o único gate é `npm run build` (`tsc -b`, valida apenas tipos). O BFF foi validado manualmente ponta a ponta; a bateria automatizada é o item **C10** em [TRABALHO_PENDENTE.md](TRABALHO_PENDENTE.md). **Não cobrir parcialmente** antes de C10 definir a estrutura de teste.

Testes de componente (unitários do front):

| Alvo | Caminhos |
| ---- | -------- |
| `LoginBox` | "Entrar" → redirect a `/oauth2/authorization/gateway-client`; "Registrar" → navega a `/register` |
| `RegisterBox` | submit feliz → navega a `/login`; erro da API (409/400) → exibe "Dados inválidos!" |
| `ProfileBox` | loading → "Carregando usuário..."; user → card com dados; null → "Usuário não encontrado" |
| `ProtectedLayout` | loading → placeholder; 200 → renderiza `<Outlet />`; 401 → redirect a `/login` |
| `NavBar` | logout monta form oculto `POST /logout` com `_csrf` lido do cookie `XSRF-TOKEN` |
| `authClient.readCookie` | cookie presente → valor decodificado; ausente → `""` |
| `useCurrentUser` | 401 → `isError` sem retry (`retry: false` é a fonte de verdade do estado de auth) |
| `useRegister` | sucesso → navegação a `/login` |

Testes de fluxo (integração do front, com MSW):

| Fluxo | Caminhos |
| ----- | -------- |
| Registro ponta a ponta | form → `POST /v1/users/register` → redirect a `/login`; e o caminho de erro 409 |
| Estado de autenticação | `/v1/users/me` 200 → dashboard acessível; 401 → redirect a `/login` |

---

### Fora de escopo deliberado

- **`discovery-server`** — Eureka puro, sem lógica própria; testar seria testar o framework.
- **`CORSConfig` / `OpenAPIConfig`** (gateway e auth-server) — configuração declarativa sem branch.
- **Getters/setters, DTOs sem lógica, código gerado** — conforme as diretrizes de unitários desta página.
