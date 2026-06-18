# Estratégia de Testes

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Diretrizes de testes](#diretrizes-de-testes)
- [Como executar](#como-executar)
- [Cobertura (JaCoCo)](#cobertura-jacoco)
- [Armadilhas e comportamentos não óbvios do stack](#armadilhas-e-comportamentos-não-óbvios-do-stack)

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

```bash
# login-interface — todos os testes (Vitest, sem Docker)
cd login-interface && npm run test:run

# login-interface — modo watch (desenvolvimento)
cd login-interface && npm test

# login-interface — com relatório de cobertura (threshold 80%)
cd login-interface && npm run coverage
```

**Quando rodar cada categoria:**

| Momento | O que rodar |
| ------- | ----------- |
| Durante desenvolvimento | `!*IntegrationTest` — feedback em segundos; `npm test` no front |
| Antes do commit | Suite completa do módulo alterado |
| CI (pull request) | Suite completa de cada módulo (`mvn -f <módulo>/pom.xml test`) + `npm run test:run` no front |

---

## Cobertura (JaCoCo)

A cobertura do back-end é medida pelo `jacoco-maven-plugin` (versão herdada do
`spring-boot-starter-parent`, sem pin manual). Como **não há POM agregador**, o plugin é
declarado **em cada módulo** — o `prepare-agent` injeta o `argLine` que o Surefire consome
(todos os testes, inclusive os de integração com Testcontainers, rodam no Surefire — não há
Failsafe), e o `report` gera o HTML em `<módulo>/target/site/jacoco/index.html` na fase `verify`.

**Gate de cobertura.** Os três módulos de domínio (`user-service`, `authorization-server`,
`gateway`) têm a regra `check` ligada à fase `verify`: o build **falha** se a cobertura de
**linha** (counter `LINE`, escopo `BUNDLE`) cair abaixo de **70%** — o piso bloqueante do
projeto. 80% segue como meta a perseguir escrevendo testes, não como gate (evita reprovar o
build por poucos pontos). Quando um módulo ficar abaixo do piso, escreva os testes faltantes
(a skill `/suggest-tests <Classe>` ajuda) — não relaxe o limiar nem infle exclusões.

**Exclusões da métrica** (mínimas, para o gate continuar significando algo): `**/*Application.class`
(só `main`) e `**/dtos/**` (DTOs Lombok, sem lógica). Classes `@Configuration` ficam **dentro**
da métrica — os testes de integração (`@SpringBootTest`) sobem o contexto e as cobrem.

**`config-server` e `discovery-server` são report-only** — geram o relatório (`prepare-agent` +
`report`), mas **sem a regra `check`**: são código de framework (Eureka/Config Server puros,
fora do escopo de teste deliberado), não de domínio, e um gate de 70% ali seria artificial.

O gate é disparado por **`mvn verify`** (não por `mvn test`). A CI invoca
`mvn verify`, então o gate de cobertura roda automaticamente a cada PR.

```bash
# roda os testes, gera o relatório e aplica o gate (módulo de domínio)
mvn -f user-service/pom.xml verify

# só o relatório, sem reprovar (útil para medir antes de ajustar testes)
mvn -f user-service/pom.xml org.jacoco:jacoco-maven-plugin:prepare-agent test org.jacoco:jacoco-maven-plugin:report
# leia: user-service/target/site/jacoco/index.html (linha "Total", coluna Lines)
```

Cobertura de linha medida no fechamento do item (referência, não contrato): user-service ~98%,
authorization-server ~95%, gateway 100% — todos com folga sobre o piso de 70%.

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

O estado do circuit breaker vive no `CircuitBreakerRegistry` do contexto Spring, que é reusado entre as classes de teste — um circuito aberto por um teste de falha contaminaria os seguintes (fallback direto sem chamada Feign). Fix: `AbstractAuthIntegrationTest` reseta o `CircuitBreakerRegistry` entre testes.

### Resilience4j com Feign: `configs.*` vale, `instances.*` não

Com Feign + Spring Cloud CircuitBreaker (group por nome do client), a resolução de configuração só enxerga `resilience4j.*.configs.*` — blocos `instances.user-service` apenas pré-criam um circuit breaker avulso que o Feign **não usa** (o id real é derivado do método, ex.: `IUserClientgetUserByEmailString`). Por isso o `application.yml` de teste do auth-server usa `configs.user-service`. O yml de **produção** (`config-server/.../config/authorization-server.yml`) também usa `configs.user-service` (antes usava `instances.*`, inerte; o fix somou `minimumNumberOfCalls: 10` para o circuito abrir na janela de 10, antes barrado pelo default 100).

### `spring.cloud.config.enabled=false` obrigatório em `AbstractIntegrationTest`

Com a stack Docker no ar, o config-server serve a configuração de produção (Redis Sentinel com hostnames internos do Compose) ao JVM de teste. O Lettuce prioriza essa config sobre as propriedades dinâmicas do Testcontainer, causando `UnknownHostException: redis-sentinel-1` e tornando a suíte não-determinística (passa sem a stack, falha com ela). Fix: `@TestPropertySource(properties = {"spring.cloud.config.enabled=false", ...})` em `AbstractIntegrationTest` desabilita o import de configuração centralizada e isola os testes de qualquer estado externo da stack.

### Gateway em teste: OAuth2 sem rede no boot e `mockJwt` só ligado ao contexto

Dois pontos não óbvios na integração do `gateway`:

- **Boot sem discovery OIDC:** o `application.yml` de teste configura o `gateway-client` com endpoints de provider **explícitos** (`authorization-uri`/`token-uri`/`jwk-set-uri`), **sem `issuer-uri`**, e mocka o `ReactiveJwtDecoder` (`@MockitoBean`). Sem isso o contexto tentaria descobrir o issuer/JWKS no startup e o boot falharia offline.
- **`mockJwt()` não funciona contra a porta real:** os configurers de `spring-security-test` (`mockJwt`/`mockOidcLogin`) injetam o `SecurityContext` no servidor e só valem com `WebTestClient.bindToApplicationContext(...).apply(springSecurity())`. O `WebTestClient` ligado à porta (`bindToServer`) fala HTTP real e ignora o mutator. Por isso o teste de acesso autenticado usa um cliente ligado ao contexto; os demais (401, CSRF, rate limit, roteamento) usam a porta real.

---

## Suíte do front-end (`login-interface`)

**Stack:** Vitest 4 + React Testing Library + @testing-library/user-event + @testing-library/jest-dom + MSW (modo node). Ambiente jsdom. `vitest.config.ts` separado do `vite.config.ts` — o React Compiler/babel do build de produção não é carregado nos testes.

**40 testes em 14 arquivos**, cobertura 100% nas classes cobertas, threshold 80% configurado (lines/functions/branches/statements).

**Infra de teste** em `src/test/`: `setup.ts` (ciclo de vida MSW server + limpeza de cookie/mocks entre testes), `server.ts`, `handlers.ts` (handlers default `GET /v1/users/me` e `POST /v1/users/register`), `utils.tsx` (`renderWithProviders` com QueryClient isolado por teste + MemoryRouter).

**Cobertura por camada:**

| Camada | Arquivos de teste | O que verificam |
| ------ | ----------------- | --------------- |
| API (`src/api/`) | `apiAxios`, `authClient`, `userClient` | Config CSRF/credentials/baseURL; login redirect; logout via form `_csrf`; register com `X-XSRF-TOKEN`; erros |
| Hooks (`src/hooks/`) | `useCurrentUser`, `useRegister` | 200 e 401 com `retry:false`; navegação `/login` no sucesso; `isError` no erro |
| Componentes | `LoginBox`, `RegisterBox`, `NavBar`, `ProfileBox`, `ProtectedLayout` | Renderização e interações |
| Páginas + rotas | `Login`, `Register`, `Dashboard`, `router` | Integração com BrowserRouter real controlando history do jsdom |

**MSW intercepta no boundary HTTP** — testa `apiAxios`/`authClient`/`userClient` de verdade, incluindo CSRF (`X-XSRF-TOKEN`) e `withCredentials`. O login não faz fetch direto; redireciona para o gateway OAuth2 — testado via asserção sobre `window.location.href`.

**Sem E2E/Playwright** — decisão explícita de escopo; o boundary HTTP coberto pelo MSW é o substituto.

---

## Fora de escopo deliberado

- **`discovery-server`** — Eureka puro, sem lógica própria; testar seria testar o framework.
- **`CORSConfig` / `OpenAPIConfig`** (gateway e auth-server) — configuração declarativa sem branch.
- **Getters/setters, DTOs sem lógica, código gerado** — conforme as diretrizes de unitários desta página.
- **E2E / Playwright** — sem cobertura end-to-end; o boundary HTTP é coberto pelo MSW nos testes do `login-interface`.
