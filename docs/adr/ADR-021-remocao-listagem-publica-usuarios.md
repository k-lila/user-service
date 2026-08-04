# ADR-021: Remoção da listagem pública `GET /v1/users` (fechamento definitivo do G1) e dois defeitos correlatos

- **Status:** aceita
- **Data:** 2026-08-04
- **Serviço alvo:** user-service · notification-service · authorization-server
- **Tarefa relacionada:** G1 / `BLOCK-004` (resíduo) — auditoria doc↔código de 2026-08-04

> **Atualização (2026-08-04, pós-revisão — efeito colateral do fix (4) no caminho 404).** O
> `senso-critico` e o `security-reviewer` aprovaram esta ADR, mas **se contradisseram** sobre o
> caminho *not-found*; a verificação deu razão ao `senso-critico`. O `AuthenticationService` do
> user-service devolve **404** para titular inexistente **ou inativo**, e o Feign entrega esse 404
> ao mesmo fallback que trata indisponibilidade. Sem distinção, o fix (4) fez tentativas de login
> contra conta inexistente **deixarem de contar no lockout** — perda de atrito contra enumeração de
> e-mails que ninguém pretendeu, e que o comentário do `AuthorizationService` chegou a descrever ao
> contrário (afirmação que já nasceu falsa, na leva cujo motivo era exatamente isso).
>
> **Correção, em duas partes interdependentes:**
> 1. `UserClientFallbackFactory` passa a distinguir a causa: `FeignException.NotFound` →
>    `UsernameNotFoundException` (resultado de negócio, **conta** no lockout); qualquer outra
>    (500, 503, timeout, conexão recusada, circuito aberto) → `UserServiceUnavailableException`
>    (**não** conta). Usar `instanceof FeignException` **genérico** aqui devolveria 500/503 ao
>    lockout e reabriria o bug original.
> 2. `ignoreExceptions: [feign.FeignException$NotFound]` no `configs.user-service` — fecha um
>    defeito **pré-existente**: o 404 de negócio contava como falha do circuito, e alguns e-mails
>    digitados errado abriam o circuito e derrubavam o login de todos (DoS por typo).
>
> **Por que as duas juntas, e não uma:** verificado no bytecode do resilience4j 2.3.0 e do
> spring-cloud-circuitbreaker 5.0.0 — `ignoreExceptions` só tira a exceção da **contabilidade**
> (`handleThrowable` faz `releasePermission()` e retorna), enquanto `getAndApplyFallback` captura
> `Throwable` **sem filtro algum**. O fallback é invocado de qualquer forma; não há atalho por
> configuração. Guard: `deveContarNoLockout_eNaoAbrirCircuito_quando404`, que falha se qualquer uma
> das duas for revertida — verificado revertendo cada uma isoladamente.

## Contexto

### O resíduo do G1 que o ADR-016 não viu

O [ADR-016](ADR-016-leitura-pii-restrita-admin.md) (2026-06-21) fechou o **G1 — IDOR de leitura de
PII** removendo `GET /v1/users/{id}` e `GET /v1/users/email/{email}` do `UserController` e
recriando-os como ADMIN-only no `AdminController`. Mas **não viu a listagem**:

```java
@Operation(summary = "Lista todos os usuários")
@GetMapping                                   // GET /v1/users
@PreAuthorize("hasRole('USER')")
public ResponseEntity<Page<UserResponseDTO>> searchAll(Pageable pageable)
```

`SearchService.searchAll` → `findByActiveTrue` devolvia, a **qualquer usuário autenticado**, uma
página com `id`, `name`, `email`, `registrationDate`, `consentAcceptedAt`, `termsVersion`,
`emailVerified` e `emailVerifiedAt` de **toda a base ativa** — sem auditoria (nenhuma chamada ao
`AuditService` no método).

O `BLOCK-004` descrevia o risco como *"qualquer usuário autenticado itera ids/e-mails e lê PII de
toda a base (enumeração/exfiltração, insumo de phishing; impacto LGPD)"*. O ADR-016 removeu as duas
rotas que exigiam **iterar** e deixou de pé a que entrega o mesmo resultado **sem iterar nenhuma
vez** — uma requisição paginada. O vetor não só continuou aberto: ficou mais barato de explorar do
que o IDOR original.

### O dano secundário: documentação que mascarava o gap

A partir do ADR-016, três documentos passaram a afirmar o falso:

- `ADR-016` § Consequências — *"fecha o G1 — PII de terceiro deixa de ser legível por qualquer
  `USER`"* e *"`UserController` fica coeso (só dado do próprio titular)"*.
- `docs/SECURITY.md` — G1 movido de "gaps identificados" para "controles ativos".
- `CLAUDE.md` — *"`UserController` (…) só opera sobre o próprio titular autenticado"*.

Apenas `docs/SERVICOS.md` continuou correto (documentava a rota como `ROLE_USER`). Numa rodada de
manutenção anterior, o `BLOCK-004` chegou a ser marcado `RESOLVIDO` com base **nesses documentos, e
não no código** — o erro que fecha o ciclo: doc errada validando a si mesma.

Registrar isto é o ponto do ADR. A lição não é sobre `searchAll`: é que **fechar um gap por
enumeração de rotas conhecidas não é o mesmo que fechar a superfície**, e que o critério de
"fechado" tem de ser verificado contra o código, nunca contra outro documento.

### Dois defeitos correlatos, achados na mesma auditoria

**Canal interno documentado em OpenAPI (viola o [ADR-006](ADR-006-canal-interno-isolado.md)).**
O `notification-service` traz `springdoc-openapi-starter-webmvc-ui` em escopo `compile` e o YAML
servido desliga apenas `springdoc.swagger-ui.enabled: false` — `/v3/api-docs` seguia ativo,
servindo a especificação de `POST /internal/notifications/email-verification`. O
`InternalTokenFilter` cobre só `/internal/*` (`InternalTokenFilterConfig`), e a porta 8095 é
publicada em `0.0.0.0` pelo `docker-compose.override.yml`. A invariante de `docs/CONVENCOES.md`
(*"nunca exponha esse endpoint pelo gateway nem documente no Swagger"*) estava violada.

**Lockout contando indisponibilidade como senha errada.** `UserClientFallbackFactory` lança
`UsernameNotFoundException` quando o circuito abre; o `DaoAuthenticationProvider`
(`hideUserNotFoundExceptions=true`) a converte em `BadCredentialsException` **sem encadear a
causa**; o `DefaultAuthenticationEventPublisher` emite `AuthenticationFailureBadCredentialsEvent`;
o `LoginAttemptListener` incrementa o contador. Efeito: **cinco tentativas durante um outage do
user-service bloqueiam o par (conta, IP) por 15 minutos** — uma indisponibilidade vira negação de
serviço para usuários legítimos.

Dois comentários do próprio código declaram intenções incompatíveis: `AuthorizationService` diz que
tratar a falha como credencial inválida é deliberado; `LoginAttemptListener` diz que falha de
comunicação interna **não** deve contar. Ambos não podem estar certos. O segundo é o correto — o
lockout existe para conter força bruta, não para amplificar incidentes.

## Decisão

### 1. Remover `GET /v1/users` — sem substituto na superfície `USER`

Removidos os três níveis, nenhum com chamador de produção remanescente:

| Arquivo | Removido |
|---|---|
| `user-service/.../controller/UserController.java` | handler `searchAll` |
| `user-service/.../services/SearchService.java` | método `searchAll` |
| `user-service/.../repository/IUserRepository.java` | `findByActiveTrue` |

Um `USER` passa a ler **exclusivamente o próprio dado**, via `GET /v1/users/me`. A listagem
administrativa equivalente já existe desde o [ADR-014](ADR-014-admin-controller-gestao-roles-auditoria.md):
`GET /v1/admin/users`, `ROLE_ADMIN`, com filtros `active`/`name`/`email` e incluindo inativos.

**Sem versionamento `/v2/` e sem período de depreciação.** Isto é remoção de superfície insegura,
não evolução de contrato: manter a rota viva por um ciclo de compatibilidade seria manter o
vazamento aberto por um ciclo. A quebra retroativa é deliberada e mitigada pela ausência de
consumidores — o `login-interface` consome apenas `/v1/users/register` e `/v1/users/me`, e o
gateway roteia pelo prefixo genérico `/v1/users/**`, sem alteração.

### 2. `GlobalExceptionHandler` passa a tratar `HttpRequestMethodNotSupportedException`

O `GlobalExceptionHandler` é um `@RestControllerAdvice` puro (não estende
`ResponseEntityExceptionHandler`) com um catch-all `@ExceptionHandler(Exception.class)`. Como o
`ExceptionHandlerExceptionResolver` roda **antes** do `DefaultHandlerExceptionResolver`, um
`GET /v1/users` após a remoção — o path continua mapeado para `PUT` — cairia no catch-all e
retornaria **500 com log ERROR**, em vez de 405.

Adicionado handler dedicado devolvendo **405** com `LOGGER.warn`. Efeito colateral positivo: fecha
um defeito latente pré-existente, em que **todo** 405 do user-service virava 500 — ruído de ERROR e
alarme falso de SLO a cada probe ou scanner.

### 3. `notification-service`: springdoc removido do classpath

Removida a **dependência** `springdoc-openapi-starter-webmvc-ui` do `pom.xml`, junto das anotações
`@Tag`/`@Operation` do `NotificationController` e do bloco `springdoc:` do YAML servido.

Desligar `springdoc.api-docs.enabled` por configuração **não** bastaria: o `application.yml` local
importa a config com `optional:configserver:`, então uma propriedade que more só no config-server
evapora se ele estiver indisponível no boot — reabrindo `/v3/api-docs` **e** a swagger-ui. Sem a
dependência, a garantia é de **classpath**, não condicional à disponibilidade de outro serviço, e
dispensa teste de integração (coerente com "notification-service é stateless, sem testes próprios").

Ficam registrados os **dois mecanismos** válidos no ecossistema, conforme o serviço publique ou não
documentação: `@Hidden` quando publica (precedente: `InternalUserController` no user-service, cujo
`/v3/api-docs` é legítimo porque o gateway o agrega) e **ausência da dependência** quando não
publica.

### 4. `authorization-server`: indisponibilidade deixa de alimentar o lockout

Nova exceção:

```java
public class UserServiceUnavailableException extends InternalAuthenticationServiceException
```

`UserClientFallbackFactory` passa a lançá-la no lugar de `UsernameNotFoundException`. O
`AbstractUserDetailsAuthenticationProvider` repropaga `InternalAuthenticationServiceException`
**intacta** (não a converte em `BadCredentialsException`), e o `DefaultAuthenticationEventPublisher`
resolve o evento por **nome exato de classe** — sem mapping para ela, nenhum evento é publicado e o
`LoginAttemptListener` nunca dispara. O `AbstractAuthenticationProcessingFilter` a captura e
preserva o redirect para `/login?error`, sem 5xx.

Uma flag consultada pelo listener seria inviável: como a causa não é encadeada na conversão, o
evento não carrega nada que distinga outage de senha errada — só um `ThreadLocal` resolveria, e
seria frágil e propenso a vazar entre requests.

`AuthorizationService` teve o primeiro `catch` ampliado de `UsernameNotFoundException` para
`AuthenticationException`. **Sem isso o fix é inerte**: a exceção nova não casaria o catch
específico, cairia no `catch (Exception)` seguinte e seria convertida de volta em
`UsernameNotFoundException` — com todos os testes existentes passando.

**A exceção não encadeia `cause` — e isso é obrigatório, não estilo.** A primeira versão do fix
passava a causa real (`super(message, cause)`) e quebrou 3 dos 4 testes de integração do circuit
breaker com `SerializationException: Cannot serialize`. Motivo: o
`SimpleUrlAuthenticationFailureHandler` guarda a `AuthenticationException` na sessão HTTP sob
`WebAttributes.AUTHENTICATION_EXCEPTION`, e a sessão é Spring Session + Redis com serialização
JDK — a cadeia Feign/Resilience4j não é serializável. Em produção isso estouraria o
`RedisSessionRepository.save` em **todo** login falho durante um outage, trocando o bug do lockout
por um pior. A causa continua registrada no `WARN | [CIRCUIT-BREAKER]` do fallback, imediatamente
antes do `throw`. **Não reintroduzir o construtor com `Throwable`.**

## Consequências

- **Positivo — G1 fechado de fato.** Nenhuma superfície `USER` devolve PII de terceiro. O
  `UserController` fica de fato coeso, e a afirmação que o ADR-016 fez prematuramente passa a ser
  verdadeira.
- **Positivo — canal interno indescobrível.** A especificação do endpoint interno do
  notification-service deixa de ser servida a quem alcance a porta 8095.
- **Positivo — lockout volta a significar só "senha errada".** Um outage do user-service não
  bloqueia mais contas legítimas; o contador mede força bruta, não incidente de infraestrutura.
- **Positivo (colateral) — 405 correto.** Todo método não suportado no user-service deixa de virar
  500 + ERROR.
- **Negativo — quebra retroativa não versionada** em `GET /v1/users` (aceita; sem consumidores).
- **Dívida NÃO fechada aqui — a listagem admin continua não auditada.** `GET /v1/admin/users` não
  emite `ADMIN_READ_USER` (só as leituras por id/e-mail emitem). É dívida herdada do ADR-011 /
  ADR-014 e permanece aberta: um ADMIN ainda pagina PII sem deixar rastro na trilha LGPD. Registrada
  em `docs/SECURITY.md`.
- **Dívida NÃO fechada aqui — `/actuator/**` sem guarda.** O fix (3) tira o `/v3/api-docs` do
  notification-service, mas o actuator continua na **mesma porta 8095**, sem Spring Security (o
  filtro cobre só `/internal/*`). O mesmo vale para o `/actuator/**` em `permitAll()` no
  authorization-server. Só o gateway isola o actuator em porta de management própria (8181).
  Registrado como gap aberto.
- **Observabilidade:** `AuthFailureListener` deixa de logar as falhas de outage, porque nenhum
  evento é publicado. Compensado pelo `WARN | [CIRCUIT-BREAKER]` do próprio fallback e pelo `ERROR`
  do `AbstractAuthenticationProcessingFilter`. Esperar queda no volume de
  `AuthenticationFailureBadCredentialsEvent` durante incidentes do user-service — é o sinal do fix,
  não regressão.
- **Serviços consumidores afetados:** nenhum. Front-end e gateway inalterados; o doc agregado
  `/v3/api-docs/user` perde uma operação.
- **Testes de regressão:** `searchAll_deveRetornar405_quandoRotaRemovida` (`UserControllerTest`);
  `deveNaoConverterEmUsernameNotFound_quandoUserServiceIndisponivel` (`AuthorizationServiceTest`) —
  guard do `catch` ampliado, sem ele o fix (4) regride em silêncio;
  `naoDeveBloquearConta_apos5FalhasDuranteOutage` (`UserServiceCircuitBreakerIntegrationTest`) — o
  teste de cruzamento lockout × circuit breaker, que falha antes do fix e passa depois.
- **ADRs afetados** (notas de atualização, texto original preservado): ADR-001 (a cláusula de
  listagem some; a invariante "leitura só de ativos" sobrevive em `searchById`/`searchByEmail`),
  ADR-004 (o fallback muda de exceção), ADR-006 (invariante reforçada), ADR-016 (a resolução
  incompleta).

## Alternativas consideradas

- **Mover `GET /v1/users` para `/v1/admin/`** — descartada. Duplicaria `GET /v1/admin/users`
  (ADR-014) com DTO e semântica de `active` divergentes: uma listagem só-ativos com
  `UserResponseDTO`, outra completa com `AdminUserResponseDTO`. Duas listagens administrativas
  quase-iguais é convite a drift, e a segunda nasceria sem os filtros da primeira.
- **Manter a rota com filtro de titularidade** — sem sentido: uma listagem restrita ao próprio
  titular é exatamente `GET /v1/users/me`.
- **Reduzir o `UserResponseDTO` público** — já havia sido descartada no ADR-016, pelo mesmo motivo:
  nome e e-mail sozinhos já bastam como insumo de phishing.
- **Manter `SearchService.searchAll` sem rota** — descartada. No ADR-016 houve razão real para
  preservar `searchByEmail` sem consumidor HTTP (ele sustenta o cache `usersByEmail`). Aqui não há
  nada análogo: viraria código morto documentado como vivo — precisamente o defeito que esta
  auditoria flagrou em `EmailVerificationService.resend(String)`.
- **(3) Desligar só `springdoc.api-docs.enabled` no YAML** — descartada pelo `optional:configserver:`
  (ver Decisão 3).
- **(3) Ampliar o `InternalTokenFilter` para `/v3/api-docs/*`** — descartada: protegeria o doc mas
  manteria a maquinaria OpenAPI num serviço que não publica documentação alguma, e daria duas
  responsabilidades a um filtro de guarda de canal.
- **(4) Flag/marcador consultado pelo `LoginAttemptListener`** — inviável, verificado na fonte do
  Spring Security: a `BadCredentialsException` é criada **sem encadear a causa**, então o evento não
  carrega informação que distinga os dois casos.
- **(4) Mapear a exceção nova para `AuthenticationFailureServiceExceptionEvent`** (via
  `setAdditionalExceptionMappings`) para preservar o log do `AuthFailureListener` — adiada. O
  lockout continuaria correto (escuta só `BadCredentials`), mas acrescenta configuração para
  recuperar um log que o fallback e o filtro já emitem. Fica como opção se a observabilidade de
  falhas de login sob outage se mostrar insuficiente.
