# Avaliação Honesta — Sistema de Microsserviços de Usuários

## Veredito

**Nível: Sênior — com ressalvas em segurança operacional e CI/CD.**

O projeto deu um salto qualitativo desde a avaliação anterior: o bloqueador estrutural que impedia o rótulo "Sênior" foi removido por completo. A escala horizontal agora é real — JWK com `kid` fixo e persistente, estado OAuth em PostgreSQL (JDBC), sessão em Redis Sentinel, Eureka HA, config-server atrás de nginx, MongoDB replica set. Somam-se as correções C1–C5 e o circuit breaker C7. O rótulo "pronto para produção" tornou-se defendível no plano técnico-arquitetural; o que resta são lacunas de segurança operacional e automação — reais, mas distintas de falhas de arquitetura ou de execução de núcleo.

---

## O que chegou ao nível Sênior

**1. Escala horizontal real e documentada.** JWK com `kid` estável carregado de PEM via `RsaKeyConverters` (`JWKConfig.java`), `JdbcRegisteredClientRepository` + `JdbcOAuth2AuthorizationService` + `JdbcOAuth2AuthorizationConsentService` (`OAuth2ClientConfig.java`), Spring Session no Redis (`@EnableRedisHttpSession`). Instâncias A e B validam mutuamente seus JWTs; o código de autorização emitido por uma instância pode ser trocado por outra. Isso resolve o ponto mais grave da avaliação anterior.

**2. Infra HA sem SPOFs.** MongoDB replica set `rs0` (3 nós + keyfile), Redis Sentinel (3 nós + 3 sentinels), Eureka em peer replication, config-server atrás de nginx (`config-lb`). A documentação das armadilhas de mounts `:ro` (entrypoint override para keyfile MongoDB, cópia do sentinel.conf para `/tmp`) é exatamente o tipo de detalhe operacional que separa quem entende o runtime de quem segue tutorial.

**3. Resiliência Feign (C7).** `IUserClient` com `fallbackFactory = UserClientFallbackFactory.class`; Resilience4j configurado no config-server (`slidingWindowSize: 10`, `failureRateThreshold: 50%`, `waitDurationInOpenState: 10s`, `timeoutDuration: 3s`). Indisponibilidade do user-service retorna `UsernameNotFoundException` imediatamente — sem timeout travado. `UserClientFallbackFactoryTest` cobre o factory com unitários.

**4. Graceful shutdown + probes de aplicação.** `server.shutdown=graceful` em todos os serviços; readiness/liveness via actuator. Combinado com os `healthchecks` e `depends_on: service_healthy` do compose, o ciclo de restart não corrompe estado nem derruba dependentes prematuramente.

**5. Bugs latentes corrigidos (C1–C5).**
- C1: índice único de e-mail garantido no MongoDB (com teste de índice).
- C2: `AuthorizationService` separa usuário inativo (`UsernameNotFoundException`) de falha Feign — deixou de engolir o erro com mensagem vazando detalhe interno.
- C3: `GlobalExceptionHandler` trata `MethodArgumentNotValidException` com 400 (deixou de sair no formato default do Spring).
- C4: `DuplicateKeyException` mapeada para 409 no registro — corrida `findByEmail → insert → 500` eliminada.
- C5: `/internal` protegido por `X-Internal-Token` (`InternalTokenFilter` + comparação em tempo constante via `MessageDigest.isEqual` + `FeignConfig` injetando o header) — endpoint que expunha `passwordHash`/`roles` deixou de ser canal aberto.

**6. Topologia, OAuth2, BFF e observabilidade** — mantidos da avaliação anterior e continuam sendo o piso que coloca o projeto acima de Pleno: separação de responsabilidades rígida, authorization code + PKCE + OIDC, BFF com token nunca tocando o browser, Zipkin/Prometheus/Grafana, logs estruturados com mascaramento de PII.

---

## O que ainda segura o "Sênior pleno" (lacunas remanescentes)

**1. Segurança operacional com múltiplos gaps abertos.**
- Sem HTTPS/TLS (G1): JWT, cookies de sessão (`SESSION`, `AUTHSESSION`) e tokens internos trafegam em claro; flag `Secure` nos cookies não tem efeito sem TLS.
- Portas internas publicadas no host (G2/C16): `8090`, `8082`, `8888`, `9091` atingíveis diretamente, contornando o gateway e seu rate limiting.
- Secrets em `docker-compose.yml` (G4/C11): credenciais Mongo, Postgres, `OAUTH_CLIENT_SECRET` e `INTERNAL_API_TOKEN` em claro no repositório.
- CORS duplicado e hardcoded em 3 módulos (G7/C12): curativo aplicado (Opção A — allowlist no user-service), mas a solução limpa (CORS só na borda, configurável por env) ainda é pendente.
- Actuator sem restrição na borda pública (G6/C18); config-server sem auth (G3/C17); sem lockout de login (G10/C19).

**2. RFC 7807 ainda pendente (C9).** O `GlobalExceptionHandler` passou a tratar `@Valid` (C3), mas os demais casos — 404, 409, 500 — ainda devolvem `String` crua. O contrato de erro não é uniforme.

**3. Cobertura de testes desigual (C10).**
- Gateway: apenas `contextLoads` (e não-hermético — faz OIDC discovery real contra o auth-server, falha em CI).
- Authorization-server: `AuthorizationServiceTest` + `UserClientFallbackFactoryTest`; falta teste de integração do circuit breaker (WireMock + Resilience4j em modo acelerado simulando user-service fora do ar).
- Front-end: zero cobertura (sem `vitest`, sem script `test` no `package.json`).

**4. Sem CI/CD.** Nenhum pipeline automatizado. Para um template reutilizável, o gate "passa nos 5 módulos + front a cada push" ainda não existe — e o `contextLoads` não-hermético do gateway vai quebrar o pipeline quando ele vier.

**5. Detalhes de execução menores (nenhum bloqueador isolado, mas acumulam).**
- `permissions` hardcoded `["users.read","users.write"]` para todo usuário no `TokenCustomizerConfig` (G8/C8): ADMIN e USER recebem o mesmo claim de permissão no token.
- Dupla chamada Feign por login (C14): `loadUserByUsername` + `jwtCustomizer` fazem `getUserByEmail` separadamente; mitigada pelo cache `authByEmail`, mas ainda são duas viagens por login sem cache frio.
- Validação de senha inconsistente (G9/C13): `@Size(min=8)` nullable + null-check manual no `RegisterService`; sem regra de complexidade.
- Campos `private` não-`final` em `UserController`/`SearchService` e `@Autowired` redundante no construtor (C15).

---

## Como cravar "Sênior pleno"

A distância restante é menor do que a já percorrida. Em ordem de impacto:

1. **Fechar os gaps de segurança que dependem só de código** (C11, C12, C16, C18): secrets em `.env` git-ignored, CORS só na borda e configurável por env, portas internas removidas do compose de prod, actuator restrito. Nenhum exige redesenho.
2. **CI/CD** (GitHub Actions): build + testes dos 5 módulos Java + front a cada push. Exige tornar o `contextLoads` do gateway hermético (pré-condição para o pipeline não quebrar no primeiro dia).
3. **RFC 7807 (C9)**: unificar 400/404/409/500 em `ProblemDetail` — refactor de 1 arquivo.
4. **Cobrir gateway e auth-server (C10)**: `contextLoads` hermético, teste WireMock do circuit breaker, `vitest` + `@testing-library/react` no front.
5. **C8, C13, C14, C15** — menores; o acúmulo é que pesa.

---

## Resumo em uma linha

> **O bloqueador estrutural foi removido: a escala horizontal é real e a infra HA está no lugar.** O sistema subiu de "Pleno consolidado com traços de Sênior" para **Sênior**, com ressalva em segurança operacional (HTTPS, secrets, portas, CORS) e ausência de CI/CD — lacunas acionáveis, não de arquitetura.
