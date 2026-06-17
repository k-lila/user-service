# RELATÓRIO B — Fechamento do ciclo de vida da conta

> Escopo: **confirmação de e-mail** + **reset de senha**, as duas peças table-stakes. Ambas
> compartilham a mesma infraestrutura (token de uso único + envio assíncrono), por isso
> entregam-se juntas. Aterrado no modelo e no fluxo atuais.

## Onde o código está hoje (lacunas mapeadas)

- **`User`** (`user-service/.../domain/User.java`): tem `active` (boolean), **não tem**
  `emailVerified` nem timestamps de credencial. `registerUser` seta `active=true`
  **imediatamente** — o usuário já nasce logável.
- **`AuthenticationService.getUserByEmail`**: rejeita `active==false` lançando
  `DomainEntityNotFound` → o auth-server trata como **credencial inválida (mensagem
  genérica)**. É o invariante "leitura somente de ativos" (ADR-001).
- **Reset de senha**: inexistente. `updateUser` troca senha, mas exige **estar logado**
  (JWT) — inútil para quem esqueceu a senha.
- **Infra de e-mail / messageria**: inexistente (`async: nenhum` no `context.json`).

## Decisão de design nº 1 — `emailVerified` separado de `active`

**Adicionar um campo distinto `emailVerified` (boolean), não reusar `active`.** Motivo: `active`
significa *conta habilitada / soft-delete* (ADR-001); `emailVerified` significa *e-mail
comprovado*. Conflatá-los quebra a semântica — reativar uma conta soft-deletada não deveria
"desverificar" o e-mail, e um usuário verificado pode ser desativado. São eixos ortogonais.

`registerUser` passa a setar `active=true, emailVerified=false`.

## Decisão de design nº 2 — verificação bloqueia login? (tensão real)

Há um conflito genuíno com o invariante de mensagem genérica:

- **Modelo estrito (recomendado):** login de e-mail não verificado **falha**. *Mas* — você
  *quer* dizer "confirme seu e-mail", e isso **vaza que a conta existe** (enumeração) e fere a
  postura de mensagem genérica do lockout.
- **Resolução:** manter o login com **falha genérica** (sem vazar), e expor o estado "não
  verificado" **apenas após o usuário provar posse da senha** ou num endpoint dedicado de
  reenvio que **sempre responde 202** (não confirma existência). A UX de "confirme seu e-mail"
  vem da tela pós-cadastro, não do erro de login.

Essa tensão (UX vs enumeração) é exatamente o tipo de decisão que **deve passar pelo
`product-manager` → `senso-critico`** antes de uma linha de código, e gera **ADR** (muda
contrato de auth + schema).

## Modelo de token (uso único, hash em repouso)

Nova coleção Mongo `account_tokens`:

```
{ tokenHash: sha256(rawToken),   // NUNCA o token cru — mesmo raciocínio do lockout
  userId, purpose: VERIFY_EMAIL | RESET_PASSWORD,
  expiresAt, usedAt (null até consumo), createdAt }
```

- **Token cru**: aleatório de alta entropia (≥ 32 bytes), enviado **só** no link do e-mail.
- **Hash em repouso**: vazamento do banco **não** permite takeover.
- **TTL**: verificação ~24h; reset ~1h (mais sensível → janela curta).
- **Uso único**: `usedAt` marca consumo; reuso é rejeitado.

## Endpoints novos (públicos, via gateway, rate-limited tier LOW)

| Endpoint | Comportamento | Cuidado de segurança |
|---|---|---|
| `POST /v1/users/register` (alterado) | cria com `emailVerified=false` + enfileira e-mail de verificação | — |
| `POST /v1/users/verify-email` `{token}` | valida token → `emailVerified=true` | token uso único + TTL |
| `POST /v1/users/verification/resend` `{email}` | **sempre 202** | **anti-enumeração** + rate-limit duro (anti e-mail bombing) |
| `POST /v1/users/password/forgot` `{email}` | **sempre 202** | **anti-enumeração** + rate-limit duro |
| `POST /v1/users/password/reset` `{token, newPassword}` | valida token → novo hash | **invalida sessões existentes** (ver abaixo) + evict `authByEmail` |

## Os pontos que iniciantes erram (e que tornam isto "padrão de indústria")

1. **Enumeração de usuário:** `forgot` e `resend` **devem** responder idêntico exista ou não o
   e-mail. Senão viram oráculo de "quem tem conta aqui".
2. **Invalidação de sessão no reset:** ao resetar senha, **derrube as sessões ativas** (Redis
   `gateway:session:*` / `authserver:session:*`) do usuário. Senão um atacante com sessão viva
   **sobrevive** ao reset — derrota o propósito.
3. **Evict de cache:** o reset muda `passwordHash` → evict `authByEmail` (o padrão de evict já
   existe no `CacheService`).
4. **Rate-limit:** `forgot`/`resend` são vetores de abuso (bombardeio de e-mail) → amarrar ao
   rate-limit LOW já existente no gateway.
5. **Link aponta para o front (BFF):** o e-mail leva ao `login-interface`, que faz proxy ao
   gateway — base-URL configurável por env (não hardcode `localhost`).

## Infra assíncrona — o "messageria" feito certo (não Kafka)

**Padrão outbox transacional + Redis Streams:**

```
register/forgot ──(transação Mongo: grava user + evento outbox)──► coleção `outbox`
                                                                       │
                                            poller / change stream ────┘
                                                                       ▼
                                                            Redis Streams (consumer group)
                                                                       ▼
                                                consumidor ──► SMTP (Spring Mail) + retry + DLQ
```

- **Por que assíncrono:** SMTP é lento e instável; o cadastro **não pode** bloquear ou falhar
  por um soluço de e-mail. Desacopla.
- **Por que outbox:** garante "gravou usuário ⇒ vai enviar e-mail" atômico (o replica set Mongo
  suporta transação multi-documento). Sem outbox, você arrisca usuário criado sem e-mail
  enviado (ou vice-versa).
- **Por que Redis Streams e não Kafka:** você **já roda Redis**; o volume de e-mail é minúsculo;
  Streams dá consumer group + ack + retry. Kafka é canhão para mosquito — entra com volume e
  múltiplos consumidores, não para confirmar e-mail. **Esta é a primeira fatia assíncrona;
  mantenha-a delimitada.**

## Decisão de design nº 3 — serviço dedicado vs dentro do user-service

| Opção | Prós | Contras |
|---|---|---|
| **`notification-service` dedicado** (recomendado p/ aprendizado) | Separação de responsabilidades correta (user-service não deve conhecer SMTP); exercita o workflow `new-service` + `security-reviewer` obrigatório; é a resposta "indústria" | Mais uma peça operacional |
| **Outbox + consumidor dentro do user-service** (pragmático p/ piloto) | Menos infra; embarca mais rápido | Acopla SMTP ao domínio; dívida a extrair depois |

Dado o valor dado ao rigor arquitetural e à intenção de *aprender*, o **`notification-service`**
é o melhor veículo — e força o caminho completo (novo serviço → ADR → `security-reviewer`).

## Implicações de governança (a esteira existente)

Isto é **mudança de schema + contrato de auth + (talvez) novo serviço** → não pule nada:

- **ADRs necessários:** (a) campo `emailVerified` + política de login pré-verificação; (b)
  coleção `account_tokens` + modelo de token hasheado; (c) padrão outbox/Streams; (d)
  `notification-service` se for serviço novo.
- **Workflow:** `feature` (ou `new-service` para o notification) → `product-manager →
  senso-critico → techlead → qa-tester → security-reviewer` (**obrigatório** — toca auth,
  tokens, sessão) `→ senso-critico → doc-keeper`.
- **Testes (qa-tester):** geração/expiração/uso-único de token; respostas **anti-enumeração**
  (controller); outbox+consumidor (Testcontainers + GreenMail para SMTP); **invalidação de
  sessão no reset**.

## Sequência sugerida de entrega

1. **ADR + spec** das decisões 1–3 (PM → senso-critico). É barato e evita retrabalho num fluxo
   que mexe em auth.
2. **`notification-service` + outbox + Redis Streams** com um e-mail "hello" ponta a ponta
   (prova a infra assíncrona isolada).
3. **Confirmação de e-mail** sobre essa infra.
4. **Reset de senha** (reusa token + infra; adiciona invalidação de sessão).
5. **doc-keeper** sincroniza `SERVICOS.md`, `SECURITY.md`, `context.json` (`async` deixa de ser
   "nenhum").

## Aplicação à decisão: máquina própria + Cloudflare Tunnel

> Decisão tomada: deploy na **própria máquina** via **Cloudflare Tunnel** (quick tunnel
> efêmero como ponto de partida). O Relatório B é **pós-barra** — vem depois do Tier 0/1 do
> [RELATORIOA.md](RELATORIOA.md). Esta seção registra só o que a decisão de host impõe ao
> ciclo de vida da conta; o modelo de dados e os fluxos acima **não mudam** por causa dela.

1. **SMTP exige relay autenticado (não envio direto).** IP residencial é bloqueado pelos
   provedores para envio SMTP direto e cairia em spam. O `notification-service` precisa de um
   **relay** (ex.: SendGrid, Mailgun, Amazon SES, ou SMTP do Google Workspace) com SPF/DKIM.
   **A escolha do fornecedor foi adiada** por decisão do usuário — aqui fica registrada apenas
   a **restrição** (relay obrigatório), não o fornecedor.

2. **Os links de verificação/reset dependem de URL pública estável.** O ponto 5 acima
   ("link aponta para o front / base-URL configurável por env, sem hardcode `localhost`")
   **colide com o quick tunnel efêmero**: a URL `*.trycloudflare.com` muda a cada reinício, e
   um link de e-mail apontaria para um endereço que expira. **Reforça** a conclusão do
   Relatório A: os fluxos de e-mail de B só funcionam de verdade com **named tunnel +
   domínio** no Cloudflare, não com o efêmero.

3. **Sequenciamento confirmado.** B continua **pós-barra** (depois do Tier 0/1 de A). Adiar a
   escolha de SMTP é coerente com isso — não bloqueia o fechamento da barra de segurança.

4. **O resto do modelo é independente do host.** `emailVerified` separado de `active`, a
   coleção `account_tokens` com token hasheado, o padrão outbox + Redis Streams e a
   invalidação de sessão no reset **não mudam** pela decisão de deploy em máquina própria via
   túnel — valem como descritos acima.
