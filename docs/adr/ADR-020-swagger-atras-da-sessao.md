# ADR-020: Swagger-UI atrás da sessão do BFF e remoção do `initOAuth` que vazava o client secret

- **Status:** aceita
- **Data:** 2026-08-04
- **Serviço alvo:** gateway (config servida pelo config-server), login-interface (nginx)
- **Tarefa relacionada:** G12 (achado novo) · emenda a [ADR-018](ADR-018-rota-logout-front-channel-borda.md) e [ADR-019](ADR-019-correcao-elos-login-hostname-unico.md)

## Contexto

O `/swagger-ui/*` era rota **pública** na borda. A dívida estava registrada em `docs/SECURITY.md`
como aceita: o controle previsto era o **Cloudflare Access**, que exige cartão de crédito mesmo no
plano free do Zero Trust — o operador decidiu não cadastrar, e a rota ficou aberta. A avaliação de
risco era "exposição do desenho da API" (inventário de endpoints, schemas, códigos de erro), com os
endpoints em si ainda protegidos.

**A avaliação estava incompleta.** A inspeção do que a rota efetivamente servia encontrou:

```
GET https://<origem-pública>/swagger-ui/swagger-initializer.js     (anônimo)

ui.initOAuth({"clientId":"gateway-client",
              "clientSecret":"<valor idêntico a secrets/OAUTH_CLIENT_SECRET>",
              "usePkceWithAuthorizationCodeGrant":true})
```

Origem: `springdoc.swagger-ui.oauth.client-secret: ${OAUTH_CLIENT_SECRET}` em
`config-server/src/main/resources/config/gateway.yml`. O springdoc materializa as propriedades
`springdoc.swagger-ui.oauth.*` como uma chamada `ui.initOAuth({...})` **literal** dentro do
`swagger-initializer.js` — um recurso estático servido ao browser.

Detalhe que atrasou a detecção: o segredo **não** aparece em `/v3/api-docs/swagger-config`, que é
onde se espera encontrar a configuração do Swagger. A primeira checagem daquele endpoint voltou
limpa. Só a leitura do `swagger-initializer.js` revelou o vazamento.

**Impacto.** `gateway-client` é o cliente **confidential** do BFF ([ADR-002](ADR-002-padrao-bff.md)).
Com o segredo público, a confidencialidade do cliente deixa de existir: o que ainda impede o abuso é
apenas a lista de `redirect_uri` registrada e o `requireProofKey(true)` — defesa em profundidade
operando sem a camada que deveria estar embaixo dela. Severidade **ALTA**, com exposição pública
ativa (não teórica) enquanto a origem esteve no ar.

**Observação que decide o desenho:** aquele `client-secret` era **supérfluo desde sempre**. Sob o
BFF, o "Try it out" do Swagger dispara XHR *same-origin* carregando o cookie `SESSION`, e a rota
`user-service` do `GatewayRouter` já aplica `tokenRelay()` — a chamada autentica pela sessão. O
botão *Authorize*, e portanto todo o bloco `oauth`, era resíduo de um desenho pré-BFF.

## Decisão

**1. Remover o bloco `springdoc.swagger-ui.oauth` inteiro** (e o `oauth2-redirect-url` que só servia
ao callback dele) de `gateway.yml`.

Remover o bloco **inteiro**, não apenas a linha do segredo: `gateway-client` é registrado com
`CLIENT_SECRET_BASIC`/`CLIENT_SECRET_POST` (`OAuth2ClientConfig.gatewayClient()`), então a variante
aparentemente segura — "manter `client-id` + PKCE, tirar só o secret" — produziria um botão
*Authorize* que falha com `invalid_client` no token endpoint. Pior que não ter.

**2. `/swagger-ui/**`, `/swagger-ui.html` e `/v3/api-docs/**` saem do `permitAll()`** do
`SecurityConfig` do gateway e passam a exigir a sessão OAuth2 do BFF.

**3. Entry point híbrido.** O `authenticationEntryPoint` do gateway era
`HttpStatusServerEntryPoint(401)` para tudo — decisão deliberada do BFF (o SPA é cliente JSON e
decide sozinho quando iniciar o login; um 302 devolveria HTML onde o front espera JSON). Mas
`/swagger-ui/**` é **navegação de browser**: com 401 seco o operador veria uma página em branco, sem
caminho para autenticar. Passa a ser um `DelegatingServerAuthenticationEntryPoint`:

| Path | Entry point | Razão |
|---|---|---|
| `/swagger-ui/**`, `/swagger-ui.html` | `RedirectServerAuthenticationEntryPoint` → `/oauth2/authorization/gateway-client` | navegação de browser |
| todo o resto (incl. `/v3/api-docs/**`) | `HttpStatusServerEntryPoint(401)` | XHR — 302 para HTML quebraria o cliente |

`/v3/api-docs/**` fica **fora** do redirect de propósito: é o caminho que o JS da página busca. Um
302 para o HTML do login faria o `swagger-client` tentar parsear a tela de login como JSON; o 401 é
o erro legível. O caminho normal — carregar a página primeiro — já garante a sessão.

O retorno ao Swagger depois do login sai de graça: `RedirectServerAuthenticationEntryPoint` já traz
um `WebSessionServerRequestCache` por default e o `OAuth2LoginSpec` injeta o cache do
`ServerHttpSecurity` no seu success handler — instâncias distintas, mesma chave de atributo na
`WebSession`, então o request salvo é restaurado.

**4. Rotacionar o `OAUTH_CLIENT_SECRET`.** Estancar o vazamento não recupera o segredo exposto.

## Consequências

**Positivas**

- O segredo do cliente confidencial deixa de ser servido. Nenhum `initOAuth` é emitido.
- A documentação passa a ser protegida pelo **próprio IdP do projeto** — nenhum segredo estático
  novo entra no sistema. Relevante num ecossistema que já perdeu um token de túnel colado em texto
  plano: um `htpasswd` seria mais um segredo compartilhado a circular.
- O gate de acesso é a garantia **durável**: qualquer coisa que uma configuração futura empurre para
  dentro da página do Swagger deixa de ser legível por anônimos. O bug corrigido era de conteúdo; o
  controle adicionado é de superfície.
- Fecha o gap "`/swagger-ui/*` e `/v3/api-docs/*` sem Cloudflare Access" por um caminho que não
  depende de plano pago.

**Negativas / resíduos**

- O botão *Authorize* continua **aparecendo** na página (vem do `securityScheme` do `OpenAPIConfig`,
  não do `initOAuth`), agora com campos vazios e sem completar o fluxo. Mantido deliberadamente: o
  `securityScheme` documenta que os endpoints exigem OAuth2, o que é informação legítima do doc.
- `OAUTH_CLIENT_REDIRECT_URIS` continua incluindo `.../swagger-ui/oauth2-redirect.html` — redirect
  URI registrado e sem uso. Inofensivo; removê-lo exigiria outro ciclo de re-seed do client.
- `OAUTH2SWAGGER_REDIRECT_URL` virou env **morta** e foi removida das duas composes e do CONFIG.md.
- O "Try it out" depende de o `fetch` do `swagger-client` enviar o cookie `SESSION`. Requisições
  same-origin mandam cookies por default (`credentials: "same-origin"`) e o `requestInterceptor` do
  springdoc já lê `document.cookie` para o `X-XSRF-TOKEN` — mas se algum upgrade mudar isso, o
  conserto é `springdoc.swagger-ui.with-credentials: true`.
- A rotação exigiu limpar `oauth2_authorization`/`oauth2_authorization_consent` e as sessões vivas no
  Redis (ver abaixo): todo mundo foi deslogado.

**Rotação executada (2026-08-04)** — o seed do `gateway-client` é idempotente **sem reconciliação**
(`findByClientId` → `save` só se ausente), então trocar o arquivo do segredo não atualiza o hash
BCrypt já persistido. Procedimento, sem `down -v` (proibido desde `docs/SECURITY.md` § re-seed):

1. `printf '%s' "$(openssl rand -hex 32)" > secrets/OAUTH_CLIENT_SECRET && chmod 644 ...`
   — **não** usar `infra/secrets/gen-secrets.sh`, que reescreve *todos* os segredos a partir de
   env-ou-default-de-dev e zeraria `REDIS_PASSWORD`, `POSTGRES_PASSWORD` etc.
2. `DELETE` em `oauth2_authorization_consent`, `oauth2_authorization` e na linha
   `gateway-client` de `oauth2_registered_client`.
3. Rebuild/recreate de `config-server-1/2` (a config vive na imagem), `gateway`,
   `authorization-server` e `interface` → re-seed com o segredo novo.
4. Limpeza das chaves `gateway:session*` e `authserver:session*` no Redis — o JWT é validado por
   assinatura, não pelo banco, então sobreviveria à limpeza do Postgres.

**Testes de regressão**

- `GatewaySecurityIntegrationTest` — `/swagger-ui/index.html` e `/swagger-ui/swagger-initializer.js`
  anônimos → 302 para `/oauth2/authorization/gateway-client`; `/v3/api-docs/user` → 401;
  `/v1/users/me` → 401 **sem** header `Location` (guarda de que o redirect não vazou para as rotas
  de API, o que quebraria o SPA silenciosamente).
- `GatewayRoutingIntegrationTest.deveReescreverPathDosApiDocsDoUserService` passou a autenticar com
  `mockJwt()` — o objeto sob verificação ali é o `rewritePath`, e sem sessão o teste pararia em 401
  antes de exercitar o roteamento.
- `ServedConfigSecretLeakTest` (**módulo config-server**) — varre as propriedades `springdoc.*` de
  todos os YAMLs servidos e falha se alguma carregar `secret`/`password`/`token`. Vive no
  config-server, e não no gateway, porque a config servida é daquele módulo: um teste no gateway
  leria o `application.yml` local, onde o bloco nunca esteve, e passaria verde com o defeito de pé.
  Tem guarda anti-vacuidade (falha se não inspecionar nenhuma propriedade).

## Alternativas consideradas

**`auth_basic` (htpasswd) no nginx do SPA.** Cinco linhas, nenhum Java, bloqueia na camada mais
externa — a request nem chega ao gateway. É o substituto mais próximo do Cloudflare Access.
Descartada: introduz mais um segredo estático compartilhado, em arquivo, num ecossistema que já
sofreu vazamento de credencial por circulação em texto plano; e, para um blueprint, colocar um
htpasswd ao lado de um IdP funcional demonstra desconfiança na própria arquitetura. Continua sendo a
opção de menor risco de regressão se algum dia a sessão for inviável.

**Registrar um cliente público `swagger-ui` dedicado** (PKCE, sem secret) para ressuscitar o
*Authorize*. Descartada: mais um `RegisteredClient` a semear e manter, para um botão que a sessão
torna redundante.

**Cloudflare Access.** Indisponível — exige cartão de crédito no Zero Trust mesmo no plano free.

**Desligar o Swagger em deploy** (`springdoc.swagger-ui.enabled=false`). Descartada: o projeto é um
**blueprint**; a documentação navegável é parte do que ele demonstra. Esconder é diferente de
proteger.

**Manter só `client-id` + PKCE, sem o secret.** Descartada por não funcionar — ver a Decisão 1.
