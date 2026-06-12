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

**Total: 220 testes — BUILD SUCCESS em user-service, authorization-server, gateway e config-server.**

### Unitários (Mockito / reativos) — 109 testes

| Módulo | Classe de serviço | Arquivo de teste | Testes |
| ------ | ----------------- | ---------------- | ------ |
| `user-service` | `RegisterService` | `services/RegisterServiceTest.java` | 16 |
| `user-service` | `SearchService` | `services/SearchServiceTest.java` | 7 |
| `user-service` | `AuthenticationService` | `services/AuthenticationServiceTest.java` | 3 |
| `user-service` | `CacheService` (put/evict por cache; null-safe) | `services/CacheServiceTest.java` | 6 |
| `user-service` | `GlobalExceptionHandler` (6 handlers: 404/409/400/validação/403 rethrow/500) | `exceptions/GlobalExceptionHandlerTest.java` | 6 |
| `user-service` | `InternalTokenFilter` (`shouldNotFilter`, 403 sem/errado, passa com token) | `config/InternalTokenFilterTest.java` | 5 |
| `user-service` | `LogUtils.maskEmail` (válido + bordas null/blank/sem-@) | `util/LogUtilsTest.java` | 7 |
| `authorization-server` | `AuthorizationService` (inclui branch de lockout C19) | `services/AuthorizationServiceTest.java` | 8 |
| `authorization-server` | `UserClientFallbackFactory` | `clients/UserClientFallbackFactoryTest.java` | 6 |
| `authorization-server` | `LoginAttemptService` | `services/LoginAttemptServiceTest.java` | 7 |
| `authorization-server` | `TokenCustomizerConfig` (claims `userID`/`roles`/`permissions` por role) | `config/TokenCustomizerConfigTest.java` | 7 |
| `authorization-server` | `LoginAttemptListener` (guard form login vs client auth) | `listeners/LoginAttemptListenerTest.java` | 5 |
| `authorization-server` | `ClientIpResolver` (em/fora de request, remoteAddr null) | `util/ClientIpResolverTest.java` | 3 |
| `gateway` | `RateLimiterConfig` (limiters + key resolvers IP/usuário) | `config/RateLimiterConfigTest.java` | 8 |
| `gateway` | `SecurityConfig` beans (cookie sessão, filtro CSRF, logout OIDC) | `config/SecurityConfigBeansTest.java` | 7 |
| `gateway` | `RateLimitLogFilter` (log 429, precedência) | `filter/RateLimitLogFilterTest.java` | 4 |
| `gateway` | `CorrelationIdFilter` (reuso/geração de UUID) | `filter/CorrelationIdFilterTest.java` | 2 |
| `gateway` | `CORSConfig` (origin patterns, methods, credentials) | `config/CORSConfigTest.java` | 1 |
| `gateway` | `OpenAPIConfig` (scheme OAuth2, URLs, scopes) | `config/OpenAPIConfigTest.java` | 1 |

Os unitários do `gateway` rodam **sem contexto Spring** (`MockServerWebExchange` + `StepVerifier`), exceto a verificação reativa do bean de logout.

### Controller (`@WebMvcTest`) — 49 testes

MockMvc + `SecurityMockMvcRequestPostProcessors.jwt()`. Cobre status HTTP, autorização por role, extração de claims JWT e validação via Bean Validation.

| Módulo | Foco | Arquivo de teste | Testes |
| ------ | ---- | ---------------- | ------ |
| `user-service` | Endpoints públicos (`ROLE_USER` / `ROLE_ADMIN` / sem token) | `controller/UserControllerTest.java` | 45 |
| `user-service` | Endpoint interno `/internal/users/email/{email}` (200/404 com `X-Internal-Token`, 403 sem/errado) | `controller/InternalUserControllerTest.java` | 4 |

### Integração — 62 testes

Bases comuns por módulo: `AbstractIntegrationTest` no `user-service` (MongoDB `mongo:7` + Redis `redis:7-alpine`), `AbstractAuthIntegrationTest` no `authorization-server` (PostgreSQL `postgres:16-alpine` + Redis `redis:7-alpine` + WireMock standalone como dublê do user-service; `flushDb()`, `resetAll()` e reset do `CircuitBreakerRegistry` entre testes) e `AbstractGatewayIntegrationTest` no `gateway` (`@SpringBootTest(RANDOM_PORT)` + `WebTestClient`; Redis `redis:7-alpine` + WireMock como dublê dos dois downstream, resolvidos pelo `SimpleDiscoveryClient` + load balancer `lb://`; `ReactiveJwtDecoder` mockado para o boot não buscar JWKS). O `config-server` usa `@SpringBootTest` + `MockMvc` (sem containers — perfil `native` lê `classpath:/config`).

| Módulo | Foco | Arquivo de teste | Testes |
| ------ | ---- | ---------------- | ------ |
| `user-service` | Fluxo registro → busca → atualização → desativação/remoção; unicidade de e-mail; índice único no banco | `integration/UserFlowIntegrationTest.java` | 18 |
| `user-service` | Comportamento do cache Redis: popular/evictar `usersById` e `usersByEmail` | `integration/CacheIntegrationTest.java` | 14 |
| `authorization-server` | Fluxo authorization_code + PKCE ponta a ponta: feliz → JWT com claims `userID`/`roles`/`permissions` reais (`TokenCustomizerConfig`/`JWKConfig`/seed do `gateway-client`); credenciais inválidas → `302 /login?error` sem code; sem PKCE → `error=invalid_request` | `integration/OAuth2AuthorizationCodeFlowIntegrationTest.java` | 3 |
| `authorization-server` | Lockout C19 e2e: 5 falhas → 6ª com senha correta bloqueada (redirect idêntico ao de senha errada, sem vazar distinção); sucesso antes do limite remove a chave `login_fail:*`; contas e IPs distintos não se contaminam (IP via `request.setRemoteAddr`) | `integration/LoginLockoutIntegrationTest.java` | 4 |
| `authorization-server` | Circuit breaker C7: downstream 500 → `/login?error` com exatamente 1 chamada Feign (guarda o C14); timeout do TimeLimiter → falha rápida; circuito aberto → fallback direto sem nova chamada, resposta < timeout | `integration/UserServiceCircuitBreakerIntegrationTest.java` | 3 |
| `authorization-server` | Seed idempotente do `gateway-client`: re-seed não duplica o client; `redirectUris` (5) e scopes (4) intactos no Postgres | `integration/RegisteredClientSeedIntegrationTest.java` | 2 |
| `authorization-server` | Sessão Redis (Spring Session): cookie `AUTHSESSION` → chave `spring:session:sessions:<id>` no Redis; saved request sobrevive na sessão | `integration/RedisSessionIntegrationTest.java` | 2 |
| `gateway` | Roteamento via `lb://`: `/v1/users/register` → user-service, `/oauth2/**` → auth-server, rewritePath dos `/v3/api-docs/user`; propagação do `X-Correlation-ID` ao downstream | `integration/GatewayRoutingIntegrationTest.java` | 4 |
| `gateway` | Segurança BFF: rota protegida sem auth → **401 (não 302)**; `/v1/users/register` isento de CSRF; cookie `XSRF-TOKEN` emitido; preflight CORS para origem permitida; acesso autenticado liberado (`mockJwt`) | `integration/GatewaySecurityIntegrationTest.java` | 5 |
| `gateway` | Rate limiting: rajada concentrada do mesmo IP estoura o bucket LOW (2 rps, burst 5) → 429 | `integration/RateLimitIntegrationTest.java` | 1 |
| `gateway` | Smoke do contexto reativo completo (OAuth2/BFF + rotas + sessão Redis) | `GatewayApplicationTests.java` | 1 |
| `config-server` | HTTP Basic C17/G3: config exige Basic (401 sem/errado, 200 correto); `/actuator/health` aberto | `ConfigServerSecurityTest.java` | 4 |
| `config-server` | Smoke do contexto (perfil `native`) | `ConfigServerApplicationTests.java` | 1 |

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

### Gateway em teste: OAuth2 sem rede no boot e `mockJwt` só ligado ao contexto

Dois pontos não óbvios na integração do `gateway`:

- **Boot sem discovery OIDC:** o `application.yml` de teste configura o `gateway-client` com endpoints de provider **explícitos** (`authorization-uri`/`token-uri`/`jwk-set-uri`), **sem `issuer-uri`**, e mocka o `ReactiveJwtDecoder` (`@MockitoBean`). Sem isso o contexto tentaria descobrir o issuer/JWKS no startup e o boot falharia offline.
- **`mockJwt()` não funciona contra a porta real:** os configurers de `spring-security-test` (`mockJwt`/`mockOidcLogin`) injetam o `SecurityContext` no servidor e só valem com `WebTestClient.bindToApplicationContext(...).apply(springSecurity())`. O `WebTestClient` ligado à porta (`bindToServer`) fala HTTP real e ignora o mutator. Por isso o teste de acesso autenticado usa um cliente ligado ao contexto; os demais (401, CSRF, rate limit, roteamento) usam a porta real.

---

## Lacunas e cobertura planejada

Mapeamento dos caminhos (felizes, de erro e de borda) ainda **sem teste**, levantado por varredura do código de produção de todos os módulos cruzada com o inventário atual. Caminhos já cobertos não aparecem aqui.

> **Roadmap de implementação:** as fases de execução (C10.1–C10.6, com infra, dependências e critério de pronto) estão em [TRABALHO_PENDENTE.md § 3](TRABALHO_PENDENTE.md#3-qualidade-e-api).

### Visão geral por módulo

| Módulo | Cobertura atual | Lacuna dominante | Prioridade |
| ------ | --------------- | ---------------- | ---------- |
| `authorization-server` | 34 unitários + 14 integração (fluxo OAuth2 + lockout + circuit breaker + seed + sessão) | — C10.4 entregue (`TokenCustomizerConfig`, lockout, `LoginAttemptListener`, `ClientIpResolver`) | — |
| `gateway` | 23 unitários + 11 integração (rotas, rate limiting, CSRF/401, CORS, filtros) | Fluxo BFF OAuth2 ponta a ponta: login real, **TokenRelay** com `Authorization: Bearer` no downstream, logout RP-initiated (C10.7) | **3** |
| `user-service` | 118 testes (robusto) | — C10.4 entregue (`CacheService`, handlers 400/500, `InternalTokenFilter`, `LogUtils`) | — |
| `login-interface` | zero (sem test runner) | Componentes e fluxos BFF (C10.5) | **4** |
| `config-server` | `contextLoads` + 4 de HTTP Basic | — C10.6 entregue | — |
| `discovery-server` | só `contextLoads` | — fora de escopo (ver nota final) | — |

---

### Testes unitários faltantes

#### `user-service`

**Entregue (C10.4)** — `CacheService` (mock de `CacheManager`/`Cache`: put/evict por cache + null-safe), `GlobalExceptionHandler` (todos os 6 handlers, com foco no 400 `IllegalArgumentException` e no 500 genérico que **não vaza** a mensagem interna), `InternalTokenFilter` (`shouldNotFilter`, passa com token correto, 403 sem/errado) e `LogUtils.maskEmail` (válido + bordas null/blank/sem-`@`). Ver [Inventário atual](#inventário-atual). Sem lacuna unitária no módulo.

#### `authorization-server`

**Entregue (C10.4)** — `TokenCustomizerConfig` (claims `userID`/`roles`/`permissions` por role, dedup USER+ADMIN, ramo não-access_token, `ArrayList` mutável), branch de lockout C19 do `AuthorizationService` (`isBlocked=true` → `isAccountNonLocked()==false`), `LoginAttemptListener` (guard form login vs client auth, em `onFailure`/`onSuccess`) e `ClientIpResolver` (em/fora de request, `remoteAddr` null). Ver [Inventário atual](#inventário-atual). Sem lacuna unitária no módulo.

#### `gateway` (unitários reativos)

**Entregues** — `CorrelationIdFilter`, `RateLimitLogFilter`, `RateLimiterConfig` (limiters + key resolvers IP/usuário), `CORSConfig`, `OpenAPIConfig` e os beans isolados do `SecurityConfig` (cookie de sessão, filtro do cookie CSRF, logout OIDC). Ver [Inventário atual](#inventário-atual). Sem lacuna unitária no módulo.

---

### Testes de integração faltantes

#### `authorization-server`

**Integração entregue (C10.1 + C10.2)** — `AbstractAuthIntegrationTest` (Testcontainers `postgres:16-alpine` + `redis:7-alpine`, WireMock como dublê do user-service; o módulo não acessa MongoDB — só Feign) cobre o fluxo authorization_code + PKCE (C10.1) e as 4 frentes do C10.2 — lockout C19 e2e, circuit breaker C7, seed idempotente do `gateway-client` e sessão Redis — ver [Inventário atual](#inventário-atual). Sem lacuna de integração; restam os unitários pontuais do C10.4 (seção acima).

#### `gateway`

**Integração entregue** — `AbstractGatewayIntegrationTest` (`@SpringBootTest(RANDOM_PORT)` + `WebTestClient`; Testcontainers `redis:7-alpine` + WireMock como dublê dos dois downstream via `SimpleDiscoveryClient` + `lb://`) cobre roteamento, rewritePath dos api-docs, propagação do `X-Correlation-ID`, rate limiting (LOW → 429), segurança BFF (401 não-302, CSRF isento em `/register`, cookie `XSRF-TOKEN`, preflight CORS) e acesso autenticado — ver [Inventário atual](#inventário-atual).

**Lacuna remanescente (fase futura)** — fluxo BFF OAuth2 **ponta a ponta**: login real, troca de código, **TokenRelay** entregando `Authorization: Bearer` ao downstream e logout RP-initiated com `end_session_endpoint`. Exige stub de discovery OIDC/JWK e é a parte mais frágil; o logout OIDC já tem cobertura unitária (`SecurityConfigBeansTest`).

#### `config-server`

**Entregue (C10.6)** — `@SpringBootTest` + `MockMvc` (sem containers), `ConfigServerSecurityTest`: `GET /user-service/default` sem credenciais → 401, com Basic correto → 200, com Basic errado → 401, e `GET /actuator/health` aberto → 200. O `contextLoads` foi movido para o pacote correto (`com.users.configserver`) — antes, no pacote divergente, o `@SpringBootTest` não localizava o `ConfigServerApplication`. Ver [Inventário atual](#inventário-atual).

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
