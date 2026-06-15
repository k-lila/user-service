# Skill (referência): Padrões Java + Spring Boot deste projeto

> Documento de conhecimento lido por `techlead`, `senso-critico` e `security-reviewer`
> antes de agir. Não é uma skill invocável — é carregado por path. Reflete **apenas o
> stack real** da v1. As invariantes a preservar estão em
> `.claude/skills/invariants-and-contracts.md` e `docs/CONVENCOES.md`.

## Stack

- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.0, Maven (multi-módulo)
- Sem Kafka, sem gRPC, sem mensageria assíncrona — comunicação é REST/Feign

## Estrutura de pacotes (user-service como referência)

```
com.users.userservice
├── config        # CacheConfig, SecurityConfig, FeignConfig, beans
├── controller    # UserController (público), InternalUserController (interno)
├── services      # regra de negócio (RegisterService, AuthenticationService, CacheService)
├── repositories  # Spring Data MongoDB
├── domain/model  # entidade User
├── dtos          # UserRequestDTO, UserResponseDTO (Bean Validation aqui)
├── exception     # GlobalExceptionHandler + exceções de domínio
└── utils         # LogUtils (maskEmail)
```

## Tratamento de erro

- `GlobalExceptionHandler` (`@RestControllerAdvice`) centraliza o mapeamento.
- Respostas de erro em **`ProblemDetail` (RFC 9457)**: `type`, `title`, `status`,
  `detail`. Nunca retorne `String` crua de erro de um controller.

## Logs

- SLF4J parametrizado: `log.info("| [REGISTRO] | criando usuario | email: {}", LogUtils.maskEmail(email))`.
- Formato em pipe (`| [VERBO] | ação | campo: valor`); níveis INFO/WARN/ERROR/DEBUG
  convencionados em `docs/LOGS.md`.
- **PII sempre mascarada** (`LogUtils.maskEmail()`) — nunca email/nome cru em log.
- `traceId`/`spanId` via B3 (propagação automática).

## Segurança

- Bean Validation obrigatório nos DTOs de entrada (`@NotBlank`, `@Email`, `@Size`,
  validação de senha declarativa: 8–72 chars com letra e número).
- BCrypt custo 10 para hash de senha. IDs de usuário são gerados pelo MongoDB.
- Roles fixas `USER`/`ADMIN` (strings simples). `permissions` derivadas das roles no
  `TokenCustomizerConfig` do auth-server, não hardcoded.
- Endpoint interno (`/internal/users/email/{email}`) protegido por `X-Internal-Token`
  (`InternalTokenFilter`); nunca exposto pelo gateway.

## Resiliência

- Resilience4j (CircuitBreaker + TimeLimiter) em **toda chamada Feign externa**.
  Config do circuit breaker `user-service`: janela 10, threshold 50%, open 10s,
  timeout 3s. Indisponibilidade → fallback retorna `UsernameNotFoundException` imediato.

## Propriedade de dados

Cada serviço é dono exclusivo do seu banco: user-service → MongoDB; authorization-server
→ PostgreSQL (estado OAuth). O auth-server **nunca** acessa MongoDB — só via Feign.
