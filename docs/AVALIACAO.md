# Avaliação Honesta — Sistema de Microsserviços de Usuários

## Veredito

**Nível: Pleno consolidado, com vários traços de Sênior — mas ainda não Sênior pleno.**

O projeto demonstra maturidade arquitetural acima da média de um Pleno, mas é freado de cravar "Sênior" por lacunas de implementação que contradizem o rótulo de *"pronto para produção"* que o próprio README assume. A boa notícia: **quase todas essas lacunas já estão mapeadas** na seção *Trabalho Pendente* — e a capacidade de enxergá-las é, por si só, um sinal sênior.

---

## O que puxa para cima (sinais de Sênior)

**1. Topologia de microsserviços correta e completa.** Config Server → Discovery (Eureka) → Gateway como único ponto de entrada → Authorization Server + Resource Server. A ordem de boot, o `depends_on: service_healthy` e a separação de responsabilidades estão certos. Muita gente "pleno" monta isso pela metade.

**2. Separação de responsabilidades rígida e bem defendida.** O authorization-server **não toca o MongoDB** — fala com o user-service via Feign (`AuthorizationService`, `IUserClient`). Isso é uma decisão de design deliberada e correta, não acidente.

**3. OAuth2 de verdade.** Authorization Code + PKCE + refresh, OIDC, JWT com claims customizados (`TokenCustomizerConfig`), cliente confidencial. O racional escrito para escolher **BFF em vez de SPA-com-PKCE** (CLAUDE.md) é argumentação de nível sênior — cita o BCP do IETF, o trade-off de `SameSite` em dev, e o alinhamento com a escala horizontal.

**4. Estratégia de observabilidade e logs madura.** Zipkin (B3), Prometheus, Grafana, `correlationId`, logging estruturado em pipe, **mascaramento de PII (LGPD)** com `LogUtils.maskEmail()`, níveis de log convencionados. Isso raramente aparece em projetos de portfólio.

**5. Testes com camadas bem pensadas.** ~103 testes no user-service: unitários (Mockito), `@WebMvcTest` com `jwt()` post-processors, e integração real com **Testcontainers** (Mongo + Redis). O comentário sobre *visibilidade eventual do RedisCache* e o uso de Awaitility mostra entendimento profundo do runtime, não cópia de tutorial.

**6. Documentação e autoconsciência.** O CLAUDE.md cataloga gaps de segurança com severidade e decisão, e o *Trabalho Pendente* prioriza por severidade/esforço. Honestidade técnica é traço sênior.

---

## O que segura embaixo (impede o "Sênior pleno")

**1. A claim "pronto para produção" não se sustenta hoje — quebra com N instâncias.** É o ponto mais grave:
- `JWKConfig.java:25` gera **um par RSA novo a cada boot, em memória**. Cada restart invalida todos os tokens vigentes, e a instância B não valida um JWT assinado pela instância A. Isso anula a escala horizontal.
- `InMemoryRegisteredClientRepository` + `InMemoryOAuth2AuthorizationService`: o `code` emitido por uma instância não pode ser trocado por token em outra.
- Sessão de login/consent em memória.

→ Um sistema que se descreve "production-ready" e "multi-instância" não pode ter esses três. (Já estão no §1 do pendente — mas estão *pendentes*.)

**2. Tratamento de erros inconsistente e com bug latente.**
- `GlobalExceptionHandler` devolve `String` crua, sem RFC 7807/`ProblemDetail`, e **não trata `MethodArgumentNotValidException`** — erros de `@Valid` saem no formato default do Spring, divergindo de todo o resto.
- `AuthorizationService:35` — o `catch (Exception e)` **engole o `UsernameNotFoundException` de usuário inativo** junto com falhas de comunicação, e ainda faz `"...: " + e` na mensagem (vaza detalhe interno). Um usuário inativo vira erro genérico em vez de "não autorizado".

**3. Detalhes que um sênior não deixaria passar.**
- `permissions` hardcoded `["users.read","users.write"]` para *todo* usuário (`TokenCustomizerConfig:34`) — ADMIN e USER recebem as mesmas permissões no token.
- **Dupla chamada Feign por login** (`loadUserByUsername` + `jwtCustomizer`), mitigada por cache mas não resolvida.
- Validação de senha dividida: `@Size(min=8)` no DTO (nullable) + checagem manual de null no service. Inconsistente; sem regra de complexidade.
- `UserController` já usa **injeção por construtor** (`UserController.java:41-45`); resta apenas o detalhe cosmético dos campos serem `private` e **não `final`**. A crítica de "mistura de estilos" praticamente caiu.
- Corrida no registro (`findByEmail` depois `insert`) não trata `DuplicateKeyException` → 500 em vez de 409.

**4. Segurança com gaps conhecidos mas reais.** Sem HTTPS, `InternalUserController` sem autenticação expondo `passwordHash`/`roles` na porta 8090, secrets no `docker-compose.yml`, CORS hardcoded.

**5. Front-end BFF correto, faltando só testes.** *(Atualizado — antes constava "quebrado".)* O front foi reescrito no padrão BFF e funciona ponta a ponta: `authClient.ts` chama `POST /users/register` (real) e faz "login" via redirect para `/oauth2/authorization/gateway-client` — sumiu o `POST /authentication/login` inexistente. **Zero `localStorage`**: o token vive na sessão do gateway, o que **eliminou de raiz** o gap "JWT no browser" (não foi adiado). Logout RP-initiated implementado. Deixou de ser peso e virou acerto arquitetural; a única lacuna restante é a **cobertura de testes do front** (segue zero — sem script `test` no `package.json`).

**6. Sem CI/CD.** Nenhum pipeline. Para um sistema que se quer "base/template reutilizável", a ausência de gate automatizado é uma lacuna sênior.

---

## Como subir de nível

Para cravar **Sênior**, em ordem de impacto:

1. **Tornar a escala horizontal real** (o item que valida o rótulo "produção" e hoje o **único bloqueador estrutural** que sobra): chaves JWK persistentes com `kid` fixo, estado OAuth em DB (JDBC), Spring Session Redis. *Sem isso, "production-ready" é aspiracional.* Os demais itens abaixo são correções pontuais de execução, não de desenho.
2. **Padronizar erros em RFC 7807** (`ProblemDetail`) + handler de `@Valid`, e **corrigir o catch que engole o `UsernameNotFoundException`**. Resiliência: Resilience4j no Feign.
3. **CI/CD** (build + testes dos 5 módulos Java + front, com Docker para Testcontainers). É o que separa "código que roda na minha máquina" de "base reutilizável".
4. **Fechar os gaps de segurança que dependem só de código**: shared-secret no `InternalUserController`, secrets em `.env`, CORS externalizado.
5. **Cobrir gateway e authorization-server com testes** (hoje quase só `contextLoads`) e tornar o teste do gateway hermético.
6. **Permissions derivadas de roles** e eliminar a dupla chamada Feign.

Para subir de **Júnior→Pleno** (não é o caso aqui — já passou), bastaria o que o projeto **já tem**: arquitetura em camadas, testes, Docker Compose. O projeto está claramente além disso.

---

## Resumo em uma linha

> **Arquitetura e raciocínio de nível Sênior; execução e "fechamento" de nível Pleno.** O projeto sabe *o que* deveria ser feito (o *Trabalho Pendente* é excelente) — falta *fazer* os itens de Severidade Alta para que a etiqueta "pronto para produção" deixe de ser uma intenção e vire um fato. Com o front-end BFF concluído, o teto remanescente concentrou-se num único bloqueador estrutural: a **escala horizontal real**.
