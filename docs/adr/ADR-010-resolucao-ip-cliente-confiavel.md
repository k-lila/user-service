# ADR-010: Resolução de IP do cliente confiável (CF-Connecting-IP + forward-headers)

- **Status:** aceita
- **Data:** 2026-06-17
- **Serviço alvo:** gateway, authorization-server
- **Tarefa relacionada:** RELATORIOA item 1.2 (forward-headers + XFF não-falsificável)

## Contexto

O lockout anti-brute-force (auth-server) e o rate limiting (gateway) particionam por IP. A
fonte de IP era falsificável:

- **Gateway** (`RateLimiterConfig.ipKeyResolver`) lia o `X-Forwarded-For` e usava o **primeiro**
  elemento (`split(",")[0]`). Sob Cloudflare Tunnel o `cloudflared` faz **append** no XFF — então
  o leftmost é o valor que o **cliente** mandou, não o IP real. Qualquer um podia enviar
  `X-Forwarded-For: 1.2.3.4` e burlar o particionamento (rate-limit/lockout viravam teatro).
- **Auth-server** (`ClientIpResolver.currentIp`) usava `getRemoteAddr()`, que só reflete o XFF
  com `server.forward-headers-strategy` configurado — setado **apenas** no overlay
  (`docker-compose.deploy.yml`), ausente na base do config-server.

A decisão de deploy (RELATORIOA) é **máquina própria + Cloudflare Tunnel**: só o `cloudflared`
alcança o gateway/auth (a base nunca publica as portas internas), e a Cloudflare **sempre
sobrescreve** o header `CF-Connecting-IP` com o IP real do cliente, descartando qualquer valor
enviado pelo cliente.

## Decisão

Fonte de IP em duas camadas, com o mesmo contrato no gateway (reativo) e no auth-server (servlet):

1. **Header de IP confiável** — `security.trusted-client-ip-header` (default `CF-Connecting-IP`,
   externalizável via `TRUSTED_CLIENT_IP_HEADER`). É a fonte primária: não-falsificável porque a
   Cloudflare o sobrescreve e nada além da borda alcança o serviço.
2. **Fallback `forward-headers-strategy=framework`** — setado na **base** do config-server
   (`server.forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:framework}`) para gateway e
   auth-server. Quando o header confiável está ausente, o `remoteAddress`/`getRemoteAddr()` reflete
   o `X-Forwarded-For` **sanitizado por uma borda confiável**. Default `framework` é seguro sem
   proxy (sem XFF, devolve o socket).

O `X-Forwarded-For` **bruto não é mais lido diretamente** em lugar nenhum — era o vetor de spoofing.

A lógica de resolução foi centralizada:
- Gateway: novo `com.users.gateway.util.ClientIpResolver` (estático), compartilhado pelo
  `ipKeyResolver` e pelo `RateLimitLogFilter` (a chave que particiona e a que é logada concordam).
- Auth-server: `ClientIpResolver.currentIp(trustedHeader)` passou a receber o header configurado,
  injetado via `@Value` no `AuthorizationService` e no `LoginAttemptListener` (continua a fonte
  ÚNICA de IP do lockout — mesma chave Redis nos dois pontos).

**Invariante de confiança:** o header confiável só é seguro porque a borda (cloudflared) é o único
caminho até o gateway/auth. Se um serviço for exposto direto, o header passa a ser falsificável e a
guarda precisa mudar (ex.: validar o IP de origem do `cloudflared`).

## Consequências

- **Positivo:** lockout e rate-limit particionam por IP real não-falsificável sob o deploy
  escolhido. `SERVER_FORWARD_HEADERS_STRATEGY` deixa de depender só dos overlays (os que ainda o
  exportam ficam redundantes, inócuos).
- **Negativo / dívida:** acopla a fonte primária de IP ao header da Cloudflare. Deploy
  não-Cloudflare deve esvaziar/trocar `TRUSTED_CLIENT_IP_HEADER` e garantir que a borda **substitua**
  (não anexe) o `X-Forwarded-For`.
- **Observabilidade:** o log de 429 (`RateLimitLogFilter`) passa a registrar o mesmo IP que
  particiona o limite.
- **Testes:** atualizados `RateLimiterConfigTest`, `RateLimitLogFilterTest`, `RateLimitIntegrationTest`
  (gateway) e `ClientIpResolverTest` (auth); novo `ClientIpResolverTest` (gateway). Cobrem precedência
  do header confiável, fallback no remoteAddress e que o XFF bruto é ignorado.

## Alternativas consideradas

- **Só `forward-headers-strategy=framework` (sem header confiável):** vendor-neutral, mas como o
  `cloudflared` faz *append* no XFF, o leftmost que o `framework` resolve continua controlado pelo
  cliente. Mais fraco — rejeitado.
- **Confiar no XFF leftmost (status quo):** falsificável sob proxy que anexa. É o gap que motivou a
  mudança.
- **Converter `ClientIpResolver` (auth) em bean Spring:** desnecessário — manter estático preserva o
  design de "fonte única" e os callers (já beans) injetam o header configurado.
