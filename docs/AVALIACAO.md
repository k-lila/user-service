# Avaliação Honesta — Sistema de Microsserviços de Usuários

## Veredito

**Nível: Sênior — com ressalvas em CI/CD, cobertura de testes e contrato de API.**

A arquitetura e a execução do núcleo sustentam o rótulo: escala horizontal real (nenhum estado preso a processo), infraestrutura sem SPOFs, OAuth2/OIDC completo com BFF, segurança operacional tratada com critério e documentação que registra riscos e decisões com honestidade incomum. O que impede o "Sênior pleno" não é arquitetura — é automação e verificação: não existe pipeline de CI, a cobertura de testes está concentrada num único módulo e o contrato de erro da API não é uniforme. São lacunas acionáveis e bem mapeadas no próprio backlog do projeto, mas hoje elas existem.

---

## Pontos fortes

**1. Escala horizontal real.** JWK com `kid` estável carregado de PEM (`JWKConfig.java`), estado OAuth em PostgreSQL via JDBC (`JdbcRegisteredClientRepository` + `JdbcOAuth2AuthorizationService` + `JdbcOAuth2AuthorizationConsentService`), sessão HTTP/WebFlux em Redis via Spring Session. Instâncias A e B validam mutuamente seus JWTs e o código de autorização emitido por uma pode ser trocado por outra — multi-instância funciona de fato, não só no diagrama.

**2. Infra HA sem SPOFs.** MongoDB replica set `rs0` (3 nós + keyfile), Redis Sentinel (3 nós + 3 sentinels), Eureka em peer replication, config-server em duas instâncias atrás de nginx (`config-lb`). As armadilhas operacionais dos mounts `:ro` (entrypoint override para o keyfile do MongoDB, cópia do `sentinel.conf` para `/tmp`) estão resolvidas e documentadas — o tipo de detalhe que separa quem entende o runtime de quem segue tutorial.

**3. Segurança operacional com critério.** Compose base prod-safe que publica só a borda; secrets via `.env` git-ignored sem defaults (fail-fast); config-server com HTTP Basic; actuator do gateway em porta de management interna; CORS apenas onde há fetch cross-origin real, configurável por env; lockout anti-brute-force por (conta, IP) no Redis com mensagem genérica; canal interno `/internal/**` protegido por shared secret comparado em tempo constante; `permissions` do JWT derivadas das roles. A borda TLS de dev (nginx + mkcert, overlay opt-in) reproduz a topologia de prod e torna os cookies `Secure` exercíveis localmente.

**4. BFF bem justificado e completo.** O token nunca toca o browser — fica na sessão do gateway (cookie `HttpOnly`); o SPA deriva o estado de auth de `GET /users/me` e não tem `localStorage`. CSRF via cookie `XSRF-TOKEN`, logout RP-initiated, cookies de sessão com nomes distintos por serviço para evitar colisão no front-channel. É a recomendação do IETF para apps com backend, implementada de ponta a ponta.

**5. Resiliência.** Circuit breaker Resilience4j na chamada Feign do auth-server (`fallbackFactory`, janela 10, threshold 50%, open 10s, timeout 3s) — user-service fora do ar retorna `UsernameNotFoundException` imediatamente em vez de travar em timeout. Graceful shutdown + probes readiness/liveness em todos os serviços, combinados com healthchecks e `depends_on: service_healthy` no compose.

**6. Observabilidade e logs.** Zipkin (B3, 100% sampling), Prometheus (scrape 5s), Grafana com dashboards pré-provisionados, SLOs definidos. Logs estruturados em formato convencionado, com `traceId`/`spanId` e PII mascarada (`LogUtils.maskEmail()`).

**7. Documentação e governança técnica.** `GAPS_SEGURANCA.md` registra cada gap com severidade, status e decisão (aberto/aceito/curativo); `TRABALHO_PENDENTE.md` mantém backlog com IDs estáveis e não reusáveis; as convenções não-óbvias (por que dois cookies, por que `entrypoint:` e não `command:`, por que `getHostString()`) estão escritas onde quem mantém o sistema vai procurar. Projetos reais raramente têm esse nível de honestidade documental.

**8. Testes onde existem, são bons.** O user-service tem 45 unitários (Mockito), 46 de controller (`@WebMvcTest`) e 32 de integração com Testcontainers (Mongo + Redis reais), cobrindo fluxo completo e comportamento de cache. O problema não é a qualidade — é a distribuição (ver lacunas).

---

## Lacunas

**1. Sem CI/CD.** Nenhum pipeline automatizado — nem build, nem testes a cada push. Para um projeto que se propõe a ser template reutilizável "pronto para produção", o gate "passa nos 5 módulos + front" simplesmente não existe; a verificação é manual e depende da disciplina de quem commita. É a lacuna mais incoerente com o restante do projeto.

**2. Cobertura de testes concentrada num único módulo (C10).**
- **Gateway:** apenas `contextLoads` — e não-hermético: o `@SpringBootTest` faz OIDC discovery real contra o auth-server e falha fora do cenário com o stack de pé. Roteamento, rate limiting e o próprio fluxo BFF não têm nenhum teste. Esse teste vai quebrar o pipeline de CI no primeiro dia, quando ele vier.
- **Authorization-server:** três classes de unitários (`AuthorizationServiceTest`, `UserClientFallbackFactoryTest`, `LoginAttemptServiceTest`). Falta teste de integração do circuit breaker (WireMock simulando o user-service fora do ar) e qualquer cobertura de `TokenCustomizerConfig`, `JWKConfig` e `SecurityConfig` — justamente os arquivos críticos do serviço.
- **Front-end:** zero cobertura. Sem `vitest`, sem script `test` no `package.json` — a única verificação é o typecheck do build.

**3. Contrato de erro não uniforme (C9).** O `GlobalExceptionHandler` devolve `String` crua em 400/404/409/500; não há RFC 7807 / `ProblemDetail`. Para uma base sobre a qual outros serviços serão construídos, o formato de erro é parte do contrato — e hoje ele é inconsistente.

**4. Gaps de segurança remanescentes (registrados, mas reais).**
- **G1:** sem TLS em produção (o curativo de dev existe; cert ACME + domínios reais pertencem à infra de deploy).
- **G13:** Redis/Sentinel sem autenticação — guarda sessões com JWT/refresh token, hash BCrypt no cache `authByEmail` e os contadores de lockout; mitigado apenas por isolamento de rede (portas nunca publicadas).
- **G5/G12 (aceitos):** chave JWK e keyfile MongoDB de dev rastreados no repositório, com override documentado para prod.
- **G9:** validação de senha fraca (`@Size(min=8)` nullable + null-check manual, sem complexidade).

**5. Detalhes de execução menores — nenhum bloqueia, mas acumulam.**
- Dupla chamada Feign por login (C14): `loadUserByUsername` e `jwtCustomizer` buscam o usuário separadamente; mitigada pelo cache `authByEmail`, mas são duas viagens com cache frio.
- Sem versionamento de API (`/v1`) — relevante antes de novas camadas de domínio entrarem.
- Higiene cosmética (C15): campos `private` não-`final`, `@Autowired` redundante, DTO montado duas vezes em `RegisterService.updateUser`.
- Gestão de admin manual: promover ADMIN exige `updateOne` direto no MongoDB; sem isso, as rotas `ROLE_ADMIN` são inalcançáveis.

---

## Como cravar "Sênior pleno"

Em ordem de impacto:

1. **CI/CD** (GitHub Actions): build + testes dos 5 módulos Java + front a cada push. Pré-condição: tornar o `contextLoads` do gateway hermético, ou o pipeline quebra no primeiro dia. Testcontainers exige Docker no runner.
2. **RFC 7807 (C9):** unificar 400/404/409/500 em `ProblemDetail` — refactor de um arquivo.
3. **Equilibrar a cobertura (C10):** `contextLoads` hermético + testes de roteamento/rate-limit no gateway; WireMock para o circuit breaker no auth-server; `vitest` + `@testing-library/react` no front.
4. **G13** (auth no Redis) e **C13** (senha) — pequenos, fecham os gaps de segurança abertos que dependem de código.
5. **C14, C15, versionamento `/v1`** — menores; o acúmulo é que pesa.

---

## Resumo em uma linha

> **Arquitetura e segurança em nível Sênior — escala horizontal real, infra HA, BFF e hardening com critério; o que falta é o cinto de segurança da engenharia: CI/CD, cobertura de testes fora do user-service e contrato de erro uniforme.**
