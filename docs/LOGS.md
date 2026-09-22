# Estratégia de Logs

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Base](#base)
- [Níveis (convenção)](#níveis-convenção)
- [Formato e convenções de escrita](#formato-e-convenções-de-escrita)
- [Exemplos de linha](#exemplos-de-linha)
- [Correlação entre serviços](#correlação-entre-serviços)
- [PII / LGPD](#pii--lgpd)
- [Cobertura por classe](#cobertura-por-classe)

## Base

Logging via SLF4J (`LoggerFactory.getLogger(Classe.class)`, `private static final LOGGER` por classe), sempre **parametrizado** (`{}`, nunca concatenação).

## Níveis (convenção)

| Nível   | Uso                                                                                                        |
| ------- | ---------------------------------------------------------------------------------------------------------- |
| `INFO`  | Eventos de fluxo de negócio bem-sucedidos (entrada de endpoint, registro, atualização, busca encontrada, `auth` enviando credenciais) e a requisição recebida no gateway. |
| `WARN`  | Anomalias esperadas e recuperáveis: e-mail já cadastrado, entidade não encontrada (404), argumento inválido / validação falhou (400), falha de login, rejeição por rate limit (429). |
| `ERROR` | Falhas inesperadas com stacktrace: handler genérico 500, falha inesperada ao carregar usuário no auth-server (ex.: desserialização), falha ao gravar a trilha de auditoria e falha da purga OAuth. Indisponibilidade do user-service **não** é ERROR: sai como WARN `[CIRCUIT-BREAKER]` no fallback. |
| `DEBUG` | Alto volume / baixo valor operacional: operações de cache (`put`/`evict`) no `CacheService`.                |

## Formato e convenções de escrita

Padrão em **pipe**, fácil de filtrar via grep. Estrutura: `| [VERBO_HTTP] | ação | campo: valor`.

- Segmentos separados por `|`; toda mensagem **começa** com `| `.
- Verbo HTTP, quando presente, em **maiúsculas** (`POST`, `GET`, `PUT`, `DELETE`); ações de domínio em **minúsculas e pt-br** (`registrar`, `buscar`, `atualizar`, `desativação`, `deleção`).
- Chave de campo padronizada: `ID:` (sempre maiúsculo), `email:`, `nome:`, `motivo:`, `correlationId:`; múltiplos campos no mesmo segmento separados por `, ` (ex.: `nome: {}, ID: {}`).
- Pares simétricos sucesso/falha compartilham o mesmo prefixo de ação (ex.: `| busca por ID | encontrado` ↔ `| busca por ID | não encontrado`).
- Logs do fluxo de autenticação (em ambos os módulos) usam o namespace `| auth | ...` (`carregando usuário`, `enviando credenciais`, `login falhou`, `falha inesperada ao carregar usuário`, `inexistente ou inativo`, `titular inexistente ou inativo`).
- Subsistemas e rotinas de fundo usam uma **tag em maiúsculas** como primeiro segmento, para grep direto: `| ADMIN |`, `| AUDIT |`, `| OUTBOX |`, `| OUTBOX-RETRY |`, `| EMAIL-VERIFICATION |`, `| EMAIL |`, `| OAUTH-PURGE |`, `| [CIRCUIT-BREAKER] |`, `| [CORS] |`. Fora dessas tags, o texto da mensagem segue em minúsculas e pt-br.
- Sem concatenação — sempre `{}` parametrizado.

## Exemplos de linha

O prefixo `%5p [app,traceId=...,spanId=...]` vem do `logging.pattern.level` (ver abaixo); a mensagem segue o padrão pipe.
Os outros dois slots de identidade do `CONSOLE_LOG_PATTERN` do Boot — `%esb(){APPLICATION_NAME}` e `%correlationId` —
estão desligados de propósito, senão nome do serviço e trace saem duas vezes na mesma linha:

```
 INFO [user-service,traceId=a1b2...,spanId=c3d4...]  | GET | usuário autenticado | ID: 665f1c2e8a3b4c0012abcd34
 INFO [user-service,traceId=a1b2...,spanId=c3d4...]  | busca por ID | encontrado | ID: 665f1c2e8a3b4c0012abcd34
 INFO [authorization-server,traceId=...,spanId=...]  | auth | carregando usuário | email: m***@exemplo.com
 WARN [user-service,traceId=...,spanId=...]          | 409 | email já cadastrado
 WARN [user-service,traceId=...,spanId=...]          | busca por ID | não encontrado | ID: 665f1c2e8a3b4c0012abcd34
ERROR [user-service,traceId=...,spanId=...]          | 500 | erro não tratado
DEBUG [user-service,traceId=...,spanId=...]          | cache usersById | put | ID: 665f1c2e8a3b4c0012abcd34
```

## Correlação entre serviços

- O `logging.pattern.level` (definido nos `*.yml` do config-server para os quatro módulos com tracing — user-service,
  authorization-server, gateway e notification-service) inclui `traceId`/`spanId` do Micrometer.
- Os mesmos quatro `*.yml` desligam a duplicata do padrão do Boot: `logging.include-application-name: false` remove o
  `[app]` avulso e `logging.pattern.correlation: ""` remove o `%correlationId` (`[traceId-spanId]`), que o Micrometer
  Tracing liga sozinho via `logging.expect-correlation-id`. A correlação em si não muda — só deixa de ser impressa duas vezes.
- Esses IDs são propagados via **B3/Zipkin** de ponta a ponta — inclusive no salto Feign auth-server → user-service, graças ao `FeignTracingConfig` (a instrumentação automática do feign-micrometer registrava o span cliente mas não emitia os headers B3; o interceptor injeta o contexto corrente, evitando o trace órfão no user-service).
- O gateway é reativo (WebFlux): o `traceId`/`spanId` no MDC só é preenchido com `spring.reactor.context-propagation: auto` (no `gateway.yml`). Sem isso o log da borda sai com `traceId=` vazio.
- O gateway também loga o `X-Correlation-ID` na borda (`CorrelationIdFilter`), **semeado a partir do traceId B3 corrente** (fallback UUID) — um id de correlação único alinhado ao trace.

## PII / LGPD

- E-mails **nunca** são logados em claro.
- `LogUtils.maskEmail()` (um por módulo: `user-service/.../util/` e `authorization-server/.../util/`) mascara para `f***@dominio`.
- IDs de usuário (não-PII) são logados normalmente.

### Log operacional ≠ trilha de auditoria

O log SLF4J descrito aqui é **operacional**: efêmero, para diagnóstico/observabilidade, com PII
mascarada. **Distinto** da **trilha de auditoria LGPD** (coleção Mongo `auditLogs`, ADR-011), que é
**registro de negócio durável** de *quem acessou/alterou/apagou qual dado de qual titular, quando*,
consultável por titular. Não confundir: uma operação gera **ambos** — uma linha de log operacional e
(quando toca dado pessoal no escopo auditado) uma entrada em `auditLogs`. O `correlationId` (traceId
B3) liga as duas, e ao trace no Zipkin. Detalhe do escopo/modelo em [SERVICOS.md](SERVICOS.md) e
[ADR-011](adr/ADR-011-trilha-auditoria-dado-pessoal.md).

## Cobertura por classe

| Classe                   | Camada                           | Destaque                                                                             |
| ------------------------ | -------------------------------- | ------------------------------------------------------------------------------------ |
| `UserController`         | user-service / controller        | entrada dos 7 endpoints (1 INFO por rota)                                            |
| `InternalUserController` | user-service / controller        | entrada do auth interno (e-mail mascarado)                                           |
| `AdminController`        | user-service / controller        | entrada das rotas ADMIN com o **ID alvo** — quem operou sobre quem                    |
| `RegisterService`        | user-service / service           | registro/update/desativar/deletar + rejeições (WARN)                                 |
| `SearchService`          | user-service / service           | busca por ID/e-mail: encontrado (INFO) + inexistente ou inativo (WARN)               |
| `AuthenticationService`  | user-service / service           | `auth` — enviando credenciais (INFO) / inexistente ou inativo (WARN)                 |
| `CacheService`           | user-service / service           | put/evict dos 3 caches (DEBUG)                                                       |
| `GlobalExceptionHandler` | user-service / exceptions        | 404/409/400 (WARN), 500 (ERROR), 403 relançado p/ Spring Security                    |
| `AdminService`           | user-service / service           | `ADMIN` — listagem, busca, roles, auto-revogação bloqueada (WARN)                    |
| `AuditService`           | user-service / service           | `AUDIT` — só falha ao gravar a trilha (ERROR); o sucesso não loga                    |
| `EmailVerificationService` | user-service / service         | `EMAIL-VERIFICATION` — confirmação (INFO), reenvio throttled / titular ausente (WARN) |
| `NotificationDispatchService` | user-service / service      | `OUTBOX` — envio confirmado (INFO) / falha, outbox `FAILED` (WARN)                   |
| `OutboxRetryService`     | user-service / service           | `OUTBOX-RETRY` — varredura, token reemitido (INFO), teto atingido / lock indisponível (WARN) |
| `TokenRevocationService` | user-service / service           | `revogação` — epoch gravado (INFO), falha de Redis fail-open (WARN)                 |
| `NotificationClientFallbackFactory` | user-service / clients | `[CIRCUIT-BREAKER]` notification-service indisponível (WARN)                         |
| `AuthorizationService`   | authorization-server / service   | `auth` — carregando usuário (INFO) + falha inesperada ao carregar usuário com stacktrace (ERROR) |
| `UserClientFallbackFactory` | authorization-server / clients | `[CIRCUIT-BREAKER]` user-service indisponível (WARN); titular inexistente (DEBUG)    |
| `AuthorizationEndpointRevalidationFilter` | authorization-server / filter | `revalidação` — sessão invalidada com o motivo (INFO), degradação (WARN)       |
| `OAuthStatePurgeService` | authorization-server / service   | `OAUTH-PURGE` — linhas removidas (INFO), lock indisponível (WARN), falha (ERROR)     |
| `AuthFailureListener`    | authorization-server / listeners | falhas de login via `AbstractAuthenticationFailureEvent` (WARN)                      |
| `CorrelationIdFilter`    | gateway / filter                 | requisição recebida + `correlationId`                                                |
| `RateLimitLogFilter`     | gateway / filter                 | rejeições 429 (WARN)                                                                 |
| `RevocationWebFilter`    | gateway / filter                 | `revogação` — token revogado rejeitado na borda (INFO), fail-open (WARN)             |
| `EmailService`           | notification-service / service   | `EMAIL` — enviado (INFO) / falha no envio (WARN)                                     |

> Nota: o handler `@ExceptionHandler(AccessDeniedException.class)` no `GlobalExceptionHandler` **relança** a exceção — sem ele, o catch-all `Exception` transformaria os 403 do `@PreAuthorize` em 500.
