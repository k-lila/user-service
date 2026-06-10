# Estratégia de Testes

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Estado atual](#estado-atual)
- [Unitários (Mockito)](#unitários-mockito)
- [Controller (`@WebMvcTest`)](#controller-webmvctest)
- [Integração (Testcontainers)](#integração-testcontainers)
- [Visibilidade eventual do `RedisCache`](#visibilidade-eventual-do-rediscache)
- [Front-end (`login-interface`)](#front-end-login-interface)

## Estado atual

**45 unitários + 49 de controller + 32 de integração** (Testcontainers), BUILD SUCCESS em ambos os módulos.

## Unitários (Mockito)

| Serviço                                       | Arquivo de teste                                                  | Testes |
| --------------------------------------------- | ----------------------------------------------------------------- | ------ |
| `RegisterService`                             | `user-service/.../services/RegisterServiceTest.java`              | 16     |
| `SearchService`                               | `user-service/.../services/SearchServiceTest.java`                | 7      |
| `AuthenticationService` (user-service)        | `user-service/.../services/AuthenticationServiceTest.java`        | 3      |
| `AuthorizationService` (authorization-server) | `authorization-server/.../services/AuthorizationServiceTest.java` | 6      |
| `UserClientFallbackFactory` (authorization-server) | `authorization-server/.../clients/UserClientFallbackFactoryTest.java` | 6      |
| `LoginAttemptService` (authorization-server)  | `authorization-server/.../services/LoginAttemptServiceTest.java`  | 7      |

## Controller (`@WebMvcTest`)

MockMvc + `SecurityMockMvcRequestPostProcessors.jwt()`.

| Foco                                                                         | Arquivo de teste                                              | Testes |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------- | ------ |
| Status HTTP, autorização (`ROLE_USER`/`ROLE_ADMIN`/sem token), extração JWT, validação Bean Validation (400 — inclui a política de senha do C13) | `user-service/.../controller/UserControllerTest.java`         | 45     |
| Endpoint interno `/internal/users/email/{email}` (200/404 com `X-Internal-Token`, 403 sem/errado) | `user-service/.../controller/InternalUserControllerTest.java` | 4      |

> Usa `@Import({SecurityConfig.class, GlobalExceptionHandler.class})` — em Spring Boot 4.0 o slice `@WebMvcTest` não carrega essas classes automaticamente.

## Integração (Testcontainers)

MongoDB `mongo:7` + Redis `redis:7-alpine`.

| Foco                                                                             | Arquivo de teste                                            | Testes |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------- | ------ |
| Fluxo registro → busca → atualização → desativação/remoção, unicidade de e-mail e índice único no banco | `user-service/.../integration/UserFlowIntegrationTest.java` | 18     |
| Comportamento do cache Redis (popular/evictar `usersById` e `usersByEmail`)      | `user-service/.../integration/CacheIntegrationTest.java`    | 14     |

> Base comum: `AbstractIntegrationTest` sobe os containers, mocka o `JwtDecoder` e limpa Redis (`flushDb`) + os caches `usersById`/`usersByEmail` entre os testes.

## Visibilidade eventual do `RedisCache`

Neste stack (Spring Boot 4.0.1 / spring-data-redis 4.0.1 / Lettuce 6.8.1, sem `commons-pool2`):

- **Causa:** `cache.put(...)` fica visível para um `cache.get(...)` da mesma chave com atraso de ~1–3 ms.
- **Consequência no teste:** os testes de cache **não fazem read-after-write direto**.
  - Leituras declarativas (`@Cacheable`): chamam `search*` **duas vezes** (a 1ª dispara o `put`, a 2ª lê já visível).
  - Asserções sobre `put`s manuais via `CacheService`: usam `await().atMost(...).untilAsserted(...)` (Awaitility, escopo `test`).
- **Por que produção está OK:** o código de produção está correto — em produção a leitura vem em requisição HTTP posterior, então o atraso é irrelevante.

## Front-end (`login-interface`)

**Sem cobertura de testes hoje** — não há `vitest`/RTL nem script `test` no `package.json`. O único gate é o `npm run build` (`tsc -b` + `vite build`, typecheck). Bateria de testes planejada (ver [TRABALHO_PENDENTE.md](TRABALHO_PENDENTE.md), C10) para o front BFF, que já está implementado (ver seção _login-interface_ no CLAUDE.md).
