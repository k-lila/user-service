# ADR-004: Resiliência da chamada Feign auth→user com circuit breaker e fallback factory

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** authorization-server

> ADR retroativo: a decisão já está implementada e em produção no blueprint; este
> registro a formaliza para dar rastreabilidade.

> **Atualização (2026-08-04, [[ADR-021]] — mudou a exceção do fallback):** o
> `UserClientFallbackFactory` **não lança mais `UsernameNotFoundException`**. Lança
> `UserServiceUnavailableException`, que estende `InternalAuthenticationServiceException`. Motivo:
> `UsernameNotFoundException` era convertida pelo `DaoAuthenticationProvider` em
> `BadCredentialsException`, o publisher emitia `AuthenticationFailureBadCredentialsEvent` e o
> `LoginAttemptListener` **contava a falha no lockout** — cinco tentativas durante um outage
> bloqueavam a conta por 15 minutos. Uma indisponibilidade virava negação de serviço para o
> usuário legítimo. O novo tipo é repropagado intacto e não tem mapping no publisher: nenhum
> evento, nenhum contador. Detalhe importante: a exceção **não encadeia `cause`** — ela é guardada
> na sessão Redis pelo failure handler e a cadeia Feign/Resilience4j não é serializável.
>
> A decisão abaixo (circuit breaker + fallbackFactory, falha rápida em vez de timeout) permanece
> **inalterada**; só mudou o **tipo** que o fallback lança e, com ele, o efeito sobre o lockout.
> Onde este ADR diz que o fallback "conflata" indisponibilidade com credencial inválida, leia-se:
> essa conflação era o defeito, e foi corrigida.

## Contexto

No fluxo de login, o authorization-server busca as credenciais e roles do usuário no
user-service via Feign (`GET /internal/users/email/{email}` — o canal interno do
[[ADR-006]]). A separação rígida de responsabilidades impede o auth-server de acessar o
MongoDB diretamente, então essa chamada síncrona é **caminho crítico de toda autenticação**.

Sem proteção, uma indisponibilidade do user-service (fora do ar, lento, em deploy) faria
cada tentativa de login **travar até o timeout** do Feign e então escalar a uma exceção
inesperada — um **HTTP 500** na cara do usuário, em vez de uma falha de login limpa. Pior:
sob carga, as threads de login ficariam todas presas esperando o user-service.

## Decisão

Proteger a chamada com **circuit breaker Resilience4j** + **fallback factory**, em
`authorizationserver/clients/`:

- `IUserClient` declara `@FeignClient(name = "user-service",
  fallbackFactory = UserClientFallbackFactory.class)`.
- Circuit breaker habilitado por `spring.cloud.openfeign.circuitbreaker.enabled=true`, com
  **group por nome do client**. Config nomeada `configs.user-service`: janela COUNT_BASED de
  10, `minimumNumberOfCalls` 10, threshold de falha 50%, open por 10s, time limiter de 3s.
- `UserClientFallbackFactory.create(cause)` retorna um stub que lança
  `UsernameNotFoundException("user-service unavailable")` **imediatamente** quando o circuito
  está aberto ou a chamada falha. O `DaoAuthenticationProvider` trata essa exceção como
  **credenciais inválidas** — o usuário volta ao form de login, sem 500.
- `AuthorizationService.loadUserByUsername` **propaga** essa `UsernameNotFoundException` sem
  reembrulhar (ver entrada de hardening em `decisions.md` [2026-06-13]); só exceções
  realmente inesperadas viram uma `UsernameNotFoundException` genérica, sem vazar a causa.

> **Refinamento posterior (não reabrir):** com `circuitbreaker.group.enabled=true`, a
> resolução de config enxerga apenas `configs.*` — o bloco `instances.*` fica **inerte**.
> Isso foi corrigido em `decisions.md` [2026-06-12] **C20** (`instances.*` → `configs.*` +
> `minimumNumberOfCalls`). Detalhe registrado lá; aqui fica apenas a referência.

Sem mudança de contrato de API.

## Consequências

**Positivas:**
- O **login degrada graciosamente** sob falha do user-service: falha rápida e limpa (volta
  ao login) em vez de timeout longo + 500.
- O circuit breaker **protege as threads** do auth-server de ficarem presas, e dá tempo de
  recuperação ao user-service (open 10s).
- Observabilidade: estado do CB exposto via Actuator/Prometheus.

**Negativas / atenção:**
- O fallback **conflata** "user-service indisponível" com "credenciais inválidas" do ponto
  de vista do **usuário final** (mensagem genérica, intencional para não vazar). O
  diagnóstico fica no log, não na resposta.
- A config precisa ficar em **`configs.user-service`** (não `instances.*`) por causa do
  group — armadilha documentada em C20; qualquer ajuste futuro deve respeitar isso.
- `minimumNumberOfCalls` deve acompanhar o tamanho da janela (10), senão o default 100
  impede o circuito de abrir com janela de 10.

**Testes de regressão:** fallback coberto em `UserClientFallbackFactoryTest` (unitário) e o
comportamento do circuito em `UserServiceCircuitBreakerIntegrationTest` (yml de teste, já em
`configs.*`).

## Alternativas consideradas

- **Retry simples sem circuit breaker.** Descartada: retry sob indisponibilidade prolongada
  só amplifica a carga no user-service e estende o tempo até a falha — não protege as
  threads do auth-server.
- **Sem fallback (deixar a exceção propagar).** Descartada: resulta em timeout longo + HTTP
  500 no login, exatamente o que se quer evitar.
- **Bulkhead/isolamento por thread pool.** Não adotado nesta fase: o time limiter de 3s +
  circuit breaker já cobrem o caso crítico; o executor do CB foi verificado como **fora do
  caminho** da chamada (sem thread-hop — ver `decisions.md` [2026-06-13] tracing), então não
  há isolamento de pool a explorar aqui.
