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
| `ERROR` | Falhas inesperadas com stacktrace: handler genérico 500 e falha de comunicação Feign (auth-server → user-service). |
| `DEBUG` | Alto volume / baixo valor operacional: operações de cache (`put`/`evict`) no `CacheService`.                |

## Formato e convenções de escrita

Padrão em **pipe**, fácil de filtrar via grep. Estrutura: `| [VERBO_HTTP] | ação | campo: valor`.

- Segmentos separados por `|`; toda mensagem **começa** com `| `.
- Verbo HTTP, quando presente, em **maiúsculas** (`POST`, `GET`, `PUT`, `DELETE`); ações de domínio em **minúsculas e pt-br** (`registrar`, `buscar`, `atualizar`, `desativação`, `deleção`).
- Chave de campo padronizada: `ID:` (sempre maiúsculo), `email:`, `nome:`, `motivo:`, `correlationId:`; múltiplos campos no mesmo segmento separados por `, ` (ex.: `nome: {}, ID: {}`).
- Pares simétricos sucesso/falha compartilham o mesmo prefixo de ação (ex.: `| busca por ID | encontrado` ↔ `| busca por ID | não encontrado`).
- Logs do fluxo de autenticação (em ambos os módulos) usam o namespace `| auth | ...` (`carregando usuário`, `enviando credenciais`, `login falhou`, `falha Feign user-service`, `inexistente ou inativo`).
- Sem texto em CAIXA ALTA em inglês e sem concatenação — sempre `{}` parametrizado.

## Exemplos de linha

O prefixo `%5p [app,traceId=...,spanId=...]` vem do `logging.pattern.level` (ver abaixo); a mensagem segue o padrão pipe:

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

- O `logging.pattern.level` (definido nos `*.yml` do config-server para user-service, gateway e authorization-server) inclui `traceId`/`spanId` do Micrometer.
- Esses IDs são propagados via **B3/Zipkin** de ponta a ponta — inclusive no salto Feign auth-server → user-service, graças ao `FeignTracingConfig` (a instrumentação automática do feign-micrometer registrava o span cliente mas não emitia os headers B3; o interceptor injeta o contexto corrente, evitando o trace órfão no user-service).
- O gateway é reativo (WebFlux): o `traceId`/`spanId` no MDC só é preenchido com `spring.reactor.context-propagation: auto` (no `gateway.yml`). Sem isso o log da borda sai com `traceId=` vazio.
- O gateway também loga o `X-Correlation-ID` na borda (`CorrelationIdFilter`), **semeado a partir do traceId B3 corrente** (fallback UUID) — um id de correlação único alinhado ao trace.

## PII / LGPD

- E-mails **nunca** são logados em claro.
- `LogUtils.maskEmail()` (um por módulo: `user-service/.../util/` e `authorization-server/.../util/`) mascara para `f***@dominio`.
- IDs de usuário (não-PII) são logados normalmente.

## Cobertura por classe

| Classe                   | Camada                           | Destaque                                                                             |
| ------------------------ | -------------------------------- | ------------------------------------------------------------------------------------ |
| `UserController`         | user-service / controller        | entrada dos 9 endpoints                                                              |
| `InternalUserController` | user-service / controller        | entrada do auth interno (e-mail mascarado)                                           |
| `RegisterService`        | user-service / service           | registro/update/desativar/deletar + rejeições (WARN)                                 |
| `SearchService`          | user-service / service           | página/encontrado (INFO) + não encontrado (WARN)                                     |
| `AuthenticationService`  | user-service / service           | `auth` — enviando credenciais (INFO) / inexistente ou inativo (WARN)                 |
| `CacheService`           | user-service / service           | put/evict dos 3 caches (DEBUG)                                                       |
| `GlobalExceptionHandler` | user-service / exceptions        | 404/409/400 (WARN), 500 (ERROR), 403 relançado p/ Spring Security                    |
| `AuthorizationService`   | authorization-server / service   | `auth` — carregando usuário (INFO) + falha Feign user-service com stacktrace (ERROR) |
| `AuthFailureListener`    | authorization-server / listeners | falhas de login via `AbstractAuthenticationFailureEvent` (WARN)                      |
| `CorrelationIdFilter`    | gateway / filter                 | requisição recebida + `correlationId`                                                |
| `RateLimitLogFilter`     | gateway / filter                 | rejeições 429 (WARN)                                                                 |

> Nota: o handler `@ExceptionHandler(AccessDeniedException.class)` no `GlobalExceptionHandler` **relança** a exceção — sem ele, o catch-all `Exception` transformaria os 403 do `@PreAuthorize` em 500.
