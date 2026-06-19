# ADR-015: Verificação de e-mail no cadastro + notification-service

- **Status:** aceita
- **Data:** 2026-06-19
- **Serviço alvo:** notification-service (novo) | user-service | authorization-server | gateway
- **Tarefa relacionada:** TASK-NOTIFICATION-SERVICE

## Contexto

O domínio `User` já tinha os campos `emailVerified`/`emailVerifiedAt` (scaffold, ver
ADR de domínio de `feature/user-domain-fields`) e o `authorization-server` já lia
`emailVerified` em `AuthorizationService.loadUserByUsername` para decidir `enabled` no
login — um gate dormente que nunca disparava porque `RegisterService` sempre setava
`emailVerified=true` no cadastro. Não havia infraestrutura de e-mail (SMTP), serviço de
notificação, nem broker de mensageria no projeto.

Esta tarefa fecha essa dívida: cria o `notification-service` (primeira responsabilidade:
e-mail de verificação de cadastro) e ativa de fato o gate de login.

A justificativa do novo serviço (em vez de embutir SMTP no user-service) foi revisada e
aprovada pelo `senso-critico` na FASE 2 do workflow `new-service.md`: enviar e-mail é uma
responsabilidade ortogonal (infraestrutura SMTP) ao domínio de identidade/CRUD de
usuário — o projeto já segue separação rígida entre serviços (authorization-server não
acessa MongoDB; user-service não acessa PostgreSQL) e misturar SMTP no user-service
juntaria dois bounded contexts distintos.

## Decisão

### Escopo v1
Só e-mail de verificação de cadastro. Não é OTP/2FA, não é alerta de login, não é
recuperação de senha.

### notification-service
Novo módulo Maven, **stateless** (sem MongoDB/Redis/PostgreSQL próprios). Expõe
`POST /internal/notifications/email-verification`, protegido pelo mesmo shared secret
`X-Internal-Token` já usado pelo canal interno do user-service (ADR-006) — sem Spring
Security (não há outra rota autenticável no serviço), um `Filter` de servlet simples
(`InternalTokenFilter` + `FilterRegistrationBean`) basta. **Nunca exposto pelo gateway.**
Envia via `JavaMailSender` (SMTP configurável por env/secret; defaults de dev são
placeholders sem credenciais reais).

### Outbox sem poller
Nova coleção MongoDB no user-service (`notificationOutbox`):

```
id, userId, type (EMAIL_VERIFICATION), tokenHash (SHA-256, nunca o token em claro),
expiresAt, status (PENDING/SENT/FAILED/CONFIRMED/SUPERSEDED), createdAt,
lastAttemptAt, attempts, purgeAt
```

O registro é criado e processado **no mesmo evento** que o originou (cadastro ou
reenvio) — não há `@Scheduled`/scan periódico varrendo a coleção. A chamada Feign ao
notification-service é disparada via `@Async` (executor dedicado `notificationExecutor`,
não o `auditExecutor`), para nunca atrasar a resposta de `POST /v1/users/register` nem de
`POST /v1/users/resend-verification`. Falha (timeout, circuit breaker aberto, 4xx/5xx) é
isolada por `NotificationDispatchService` e só reflete no `status` do outbox — nunca
propaga ao chamador.

**Retenção (fechamento do crítico C1):** o TTL index do Mongo atua sobre `purgeAt`
(`createdAt + 30 dias`, configurável via `app.verification.outbox-retention`), não sobre
`expiresAt`. Aplicar o TTL direto em `expiresAt` apagaria registros `CONFIRMED`/`SENT`
pouco depois da expiração do token, perdendo o histórico de auditoria do outbox.

### Token de verificação
Opaco (256 bits de entropia, `SecureRandom` + Base64URL), **não é JWT** — só o hash
SHA-256 é persistido. TTL de 15 minutos (`app.verification.token-ttl`). Transportado via
query string no link de confirmação (`GET /v1/users/verify-email?token=...`).

**Decisão consciente sobre o transporte (fechamento do crítico C2):** token em URL é o
padrão de mercado para links de confirmação por e-mail (mesmo padrão usado por
provedores como GitHub/Gmail). O TTL curto + uso único (status do outbox vira
`CONFIRMED`/`SUPERSEDED` após o primeiro uso válido) mitigam a maior parte do risco de
exposição via log de proxy/histórico do navegador. Verificado que os filtros de log do
gateway (`CorrelationIdFilter`, `RateLimitLogFilter`) só logam `request.getPath().value()`
— **não** a query string — então o token não aparece em log aplicacional do gateway.
Resíduo aceito: o `http.url` de spans Zipkin e logs de borda fora do código deste
repositório (CDN/proxy) podem capturar a query string; não tratado nesta v1 por
desproporcional ao risco residual (TTL curto + uso único).

### Confirmação e reenvio (user-service)
- `GET /v1/users/verify-email?token=...` — público, pré-sessão. Resposta genérica em
  caso de token inválido/expirado/já usado (sem revelar o motivo). Idempotente (clique
  duplicado no link já confirmado é sucesso silencioso). Audita `EMAIL_VERIFIED`
  (`AuditAction`, ator USER).
- `POST /v1/users/resend-verification` `{email}` — público, pré-sessão. **Resposta HTTP
  sempre `202 Accepted` idêntica**, independente de o e-mail existir, já estar
  verificado, ou estar pendente (anti-enumeração). Não auditado em `auditLogs` (chamada
  anônima de alto volume/potencial abuso; auditar criaria ruído e um oráculo de
  volume/timing).

### Rate limit do reenvio (fechamento do BLOCK-003/B3)
Duas camadas: tier LOW por IP no gateway (mesmo de `/v1/users/register`, já existente) +
**novo limite por conta-alvo** (`ResendRateLimitService`, Redis, chave
`sha256(emailLower)`, janela fixa de 1h, limite configurável — default 3/h via
`app.verification.resend-max-per-window`). Sem o limite por conta, um atacante com IPs
rotativos poderia bombardear a caixa de e-mail de uma vítima específica (e-mail
bombing) — o projeto já tinha precedente desse padrão de contador em
`LoginAttemptService` (lockout por conta+IP). Quando throttled, a resposta ao chamador
continua a mesma `202` genérica.

### Gate de login + grace period (fechamento do BLOCK-003/B2)
`RegisterService` passa a setar `emailVerified=false` no cadastro (antes: sempre
`true`). O gate já existente em `AuthorizationService.loadUserByUsername` passa a valer
de fato — login bloqueado (`DisabledException`, mensagem genérica) enquanto não
confirmado.

Sem mitigação, isso tornaria uma conta **permanentemente inacessível** se o e-mail de
verificação nunca chegasse (SMTP fora do ar, outbox `FAILED`) e o usuário não pedisse
reenvio a tempo. Mitigação: **janela de carência (grace period) de 24h** desde o
cadastro (`security.email-verification.grace-period`, configurável) — login é permitido
mesmo com `emailVerified=false` dentro dessa janela; só bloqueia de fato depois dela sem
confirmação.

**Mudança de contrato Feign (aditiva):** o grace period precisa do timestamp de cadastro
no authorization-server, então `AuthDTO` (user-service e authorization-server, hoje
idênticos) ganha o campo `registrationDate` (`Instant`), populado em
`AuthenticationService.getUserByEmail()`. É aditiva e não-quebra (Jackson ignora campos
desconhecidos; `registrationDate=null` no lado consumidor cai no curto-circuito
`registrationDate != null && ...` → `withinGracePeriod=false`, sem NPE, equivalente ao
tratamento de usuário legado). **Ordem de deploy recomendada:** subir `user-service` e
`authorization-server` na mesma janela; mesmo se o `authorization-server` antigo (sem o
campo) rodar contra o `user-service` novo, o campo extra é simplesmente ignorado.

Usuários legados (`emailVerified=null`, anteriores a esta feature) continuam tratados
como verificados — não há migração/backfill.

### Direção do Feign (fechamento do crítico C3)
Inversão do padrão usual do projeto: aqui o **user-service é o consumidor** Feign (do
notification-service), não o provedor. Replica o padrão de resiliência já usado pelo
authorization-server → user-service: `FeignConfig` (injeta `X-Internal-Token`),
`FeignTracingConfig` (propaga o contexto de trace B3 via `Propagator` do Micrometer —
sem isso, a chamada abriria um trace órfão no notification-service, mesmo problema já
resolvido no auth-server), circuit breaker Resilience4j (`configs.notification-service`:
janela 10, threshold 50%, timeout 3s) + `NotificationClientFallbackFactory`.

## Consequências

- Cadastro nunca falha por causa de falha no envio do e-mail (isolamento de exceção no
  caminho assíncrono) — mas a UX de "conta criada, e-mail não chegou" depende do usuário
  notar e pedir reenvio (mitigado pela janela de carência de 24h).
- Novo serviço a operar/monitorar (Eureka, config-server, Prometheus, Docker secrets
  SMTP).
- `docs/SECURITY.md` precisa registrar: token em URL (risco aceito, mitigado por TTL+uso
  único), rate limit do resend (agora com duas camadas), `registrationDate` como
  PII-adjacente trafegando no canal interno.
- `AuthorizationServiceTest` precisa de casos novos para o grace period
  (verificado-dentro-da-janela / expirado-fora-da-janela / legado-null) — não há
  regressão nos cenários já existentes (verificado/não verificado fora de qualquer
  janela/legado), só extensão.
- Dívida aceita: outbox sem retry automático em background — reenvio é sempre manual
  nesta v1.

## Alternativas consideradas

- **Poller `@Scheduled` varrendo o outbox** — descartado: a UX já provê reenvio manual
  sob demanda (token de 15 min + botão de reenvio), tornando inútil um scan periódico;
  adicionaria complexidade (locking entre instâncias) sem ganho real.
- **JWT como token de verificação** — descartado: difícil invalidar antes do `exp` em
  caso de reenvio (o token antigo continuaria tecnicamente válido até expirar, exigindo
  denylist e perdendo a simplicidade que o JWT prometia). Token opaco + hash no Mongo é
  mais simples de invalidar (basta marcar o outbox como `SUPERSEDED`).
- **Persistência própria no notification-service** (log de e-mails enviados) —
  descartado: a auditoria/histórico já vive no outbox do user-service; manter o
  notification-service stateless reduz a superfície operacional do novo serviço.
- **Chamada síncrona bloqueante** (mesmo padrão do auth-server→user-service, com
  timeout 3s no caminho crítico) — descartado a favor de `@Async`: o cadastro/reenvio
  não deve esperar pela rede para responder ao cliente.
- **Decoupling total do gate de disponibilidade de envio** (manter `emailVerified`
  sempre `true`, e-mail só informativo) — descartado: o usuário decidiu ativar o
  bloqueio real de login, fechando a dívida do gate dormente em vez de deixá-lo inerte
  indefinidamente; o risco de conta inacessível foi endereçado via grace period em vez
  de desativar o gate.
