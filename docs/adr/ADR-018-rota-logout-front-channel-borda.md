# ADR-018: Rota de logout front-channel na borda (`/connect/**` no gateway)

- **Status:** aceita
- **Data:** 2026-07-28
- **Serviço alvo:** gateway (rota + `permitAll`); consumidor indireto: authorization-server
- **Tarefa relacionada:** A.4 da migração para domínio fixo via named tunnel (spec `DOMINIO.md`, removida após a conclusão da tarefa)

## Contexto

O logout do BFF é **RP-Initiated Logout** (OIDC): o `POST /logout` encerra a sessão local do
gateway e o `oidcLogoutSuccessHandler` (`gateway/.../config/SecurityConfig.java`) redireciona o
**browser** ao `end_session_endpoint` do authorization-server, passando `id_token_hint` e
`post_logout_redirect_uri` na query string. É uma navegação de front-channel: quem faz a request
é o browser, não o gateway.

Em desenvolvimento isso funciona porque o `docker-compose.override.yml` publica o auth-server em
`localhost:8082`, e o default de `OAUTH_END_SESSION_URI` aponta direto para lá
(`http://localhost:8082/connect/logout`). O browser alcança o IdP sem passar pelo gateway.

A migração para **domínio fixo com hostname único** (Cloudflare Tunnel → `interface:80` → gateway)
quebra essa premissa. Nessa topologia:

- só a origem pública existe para o browser; o authorization-server **não** é alcançável de fora
  (e isso é desejado — é o que sustenta a premissa de confiança do [ADR-010](ADR-010-resolucao-ip-cliente-confiavel.md),
  de que só o `cloudflared` chega à borda interna);
- o `OAUTH_END_SESSION_URI` precisa apontar para `${PUBLIC_ORIGIN}/connect/logout`;
- o `GatewayRouter` conhecia apenas `/v1/users/**`, `/v1/admin/**`, `/oauth2/**`, `/login` e
  `/v3/api-docs/**`. **Não havia rota para `/connect/**`** — o logout terminaria em **404** na
  borda pública, deixando a sessão do IdP viva mesmo após o SPA "deslogar".

O achado é invisível em dev justamente porque lá a URL é o auth-server direto (achado A2 do
levantamento na spec da migração, já removida).

## Decisão

Criar a rota `connect-logout` no `GatewayRouter`:

- **path:** `/connect/**`
- **destino:** `lb://authorization-server`
- **rate limit:** tier **MED** (`redisRateLimiterMed`, 5 req/s cap 10) com **`ipKeyResolver`**
- **sem `tokenRelay()`**
- **posição:** entre as rotas específicas, antes das genéricas `/v1/users/**` e `/v1/admin/**`

E adicionar `/connect/**` ao `permitAll` do `SecurityConfig` do gateway.

Racional de cada escolha:

- **`ipKeyResolver`, não `userKeyResolver`:** a request chega **sem** sessão do gateway — o
  `POST /logout` acabou de encerrá-la. O `userKeyResolver` devolveria `"anonymous"` para todo
  mundo, colapsando todos os clientes num balde único e transformando o rate limit num
  negador de serviço coletivo.
- **Tier MED, não HIGH nem LOW:** é navegação de browser em fluxo normal de uso (um logout por
  sessão), não XHR autenticado de alta frequência (HIGH) nem superfície de enumeração/abuso
  anônimo como o registro (LOW).
- **Sem `tokenRelay()`:** o `id_token_hint` viaja na query string montada pelo
  `oidcLogoutSuccessHandler`. Não há `Authorization: Bearer` a injetar, e não haveria sessão de
  onde extraí-lo.
- **`permitAll`:** sem isso o `HttpStatusServerEntryPoint` devolveria **401** e o logout jamais
  alcançaria o IdP. Não há PII nem mutação de estado próprio nesse caminho — o auth-server valida
  o `id_token_hint` por conta própria.

O contrato de rota da borda muda de forma **aditiva**: nenhum path existente é alterado ou
removido, e o default de `OAUTH_END_SESSION_URI` (`http://localhost:8082/connect/logout`) continua
válido para dev.

## Consequências

**Positivas**

- O logout fecha ponta a ponta sob hostname único: SPA → `POST /logout` → gateway →
  `${PUBLIC_ORIGIN}/connect/logout` → auth-server → retorno a `${PUBLIC_ORIGIN}/`.
- O authorization-server permanece **não exposto diretamente**, preservando a premissa de
  confiança do ADR-010 e a superfície pública mínima.
- Um caminho front-channel a mais passa a ter rate limit por IP na borda, em vez de não existir.

**Negativas / a observar**

- O prefixo `/connect/**` do Spring Authorization Server passa a ser público na borda. Hoje ele
  serve apenas o `end_session_endpoint`; se uma versão futura do SAS adicionar endpoints sob esse
  prefixo, eles ficam expostos por tabela. **Mitigação:** se isso ocorrer, estreitar a rota para
  `/connect/logout` exato.
- O `nginx.conf` do SPA precisa do `location /connect/` correspondente — sem ele o path cai no
  `try_files` do SPA e o problema apenas muda de lugar (404 do gateway vira página em branco).
- Consumidores afetados: nenhum contrato de API de domínio muda. O front-end não muda (o
  `POST /logout` continua idêntico); o que muda é para onde o gateway redireciona depois.
- **Regressão a cobrir:** teste garantindo que `connect-logout` existe no `RouteLocator` e precede
  as rotas genéricas. Os testes existentes que fixam
  `http://localhost:8082/connect/logout` (`SecurityConfigBeansTest`,
  `GatewayOAuth2FlowIntegrationTest`) seguem válidos — o **default** não mudou.

## Alternativas consideradas

- **Expor o authorization-server num hostname próprio** (`auth.exemplo.com`, segundo public
  hostname no túnel). Funciona e é a topologia clássica, mas custa um segundo ingress, reintroduz
  CORS de verdade entre as origens, espalha os cookies por dois hosts e amplia a superfície
  pública. Descartada: o hostname único é justamente o que simplifica cookies e CORS nesta leva.
- **Manter `OAUTH_END_SESSION_URI` apontando para o host interno**
  (`http://authorization-server:8082/connect/logout`). Não funciona: é o **browser** que segue esse
  redirect, e ele não resolve nomes da rede Docker.
- **Abandonar o RP-Initiated Logout** e encerrar só a sessão do gateway. Deixaria a sessão do IdP
  viva — o próximo `/oauth2/authorize` re-autenticaria em silêncio e o usuário voltaria logado sem
  digitar credenciais. Regressão de segurança inaceitável.
- **Rota sem rate limiter.** Descartada: é um path público e não autenticado na borda; sem balde,
  vira alvo gratuito de flood contra o auth-server.
