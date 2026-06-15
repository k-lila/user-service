# ADR-002: Padrão BFF — o gateway é o cliente OAuth2 e o SPA nunca manuseia JWT

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** gateway / login-interface

> ADR retroativo: a decisão já está implementada e em produção no blueprint; este
> registro a formaliza para dar rastreabilidade.

## Contexto

O front-end (`login-interface`, React 19 SPA) precisa autenticar usuários contra o
authorization-server OAuth2 do projeto. O padrão "ingênuo" para SPAs — **SPA-com-PKCE** —
faz o próprio browser conduzir o fluxo `authorization_code` e guardar o `access_token`/
`refresh_token` no front (tipicamente `localStorage` ou memória). Isso cria um gap de
segurança estrutural: qualquer XSS no SPA exfiltra o token, e o `localStorage` não é
protegível por `HttpOnly`.

O ecossistema já tem um gateway (Spring Cloud Gateway, WebFlux) como **único ponto de
entrada externo** e sessão server-side no Redis (ver [[ADR-007]]). Havia, portanto, a
oportunidade de manter o token inteiramente no backend.

## Decisão

Adotar o padrão **BFF (Backend-for-Frontend)**: o **gateway é o cliente OAuth2
confidencial** (`gateway-client`), e o SPA autentica por **sessão via cookie**, nunca
vendo o JWT.

Mecânica (`gateway/.../config/SecurityConfig.java`, `routing/GatewayRouter.java`):

- O gateway combina `oauth2Login` + `oauth2Client` + `oauth2ResourceServer(jwt)`. O fluxo
  é `authorization_code` + **PKCE** (`requireProofKey(true)` no `gateway-client`).
- O `OAuth2AuthorizedClient` (com o `access_token`/JWT) é guardado na **sessão Redis**
  (cookie `SESSION`, `HttpOnly`); o token **nunca** é serializado ao browser.
- A propagação downstream usa o filtro **`TokenRelay` por rota** (na rota `user-service`,
  `GatewayRouter`), que injeta o token da sessão como `Authorization: Bearer`. Declarado por
  rota — **não** via `default-filters` do YAML — porque rotas do `RouteLocatorBuilder` não
  recebem os default-filters.
- O SPA deriva o estado de autenticação de `GET /v1/users/me` (200 vs 401), sem enviar
  `Authorization: Bearer`, sem PKCE no front, sem `/callback` manual, sem refresh manual.
- **CSRF** via token sincronizador (cookie `XSRF-TOKEN` legível, header `X-XSRF-TOKEN`);
  o entry point de API devolve **401** (não 302) para o SPA decidir o login.
- **Logout RP-initiated**: `POST /logout` encerra a sessão local e redireciona ao
  `end_session_endpoint` do IdP.

Não há mudança de contrato de API: o BFF opera sobre os endpoints `/v1/` existentes.

## Consequências

**Positivas:**
- O **token nunca toca o browser** (fica na sessão do gateway; cookie `HttpOnly`+`Secure`+
  `SameSite`) → XSS não exfiltra JWT/refresh; elimina de raiz o gap "JWT em `localStorage`".
- É a **recomendação do IETF** (BCP OAuth para apps com backend).
- **Reaproveita o backend** existente (o gateway já é a borda) e reduz peça móvel no front
  (sem PKCE, sem `/callback`, sem refresh manual).
- Alinha com a **sessão server-side no Redis** ([[ADR-007]]).

**Negativas / atenção:**
- Depende de **sessão server-side** (Redis) — estado a operar e escalar (mitigado: já é
  Spring Session no Redis).
- Depende de **cookies de sessão distintos por serviço** (`SESSION` do gateway vs
  `AUTHSESSION` do auth-server) para não colidir no salto front-channel — ver [[ADR-007]].
- Acopla a autenticação do front à disponibilidade do gateway (que já é o único ponto de
  entrada, então não adiciona SPOF novo).

**Testes de regressão:** fluxo BFF OAuth2 ponta a ponta coberto em
`GatewayOAuth2FlowIntegrationTest` e `GatewaySecurityIntegrationTest` (Redis + WireMock via
`WebTestClient`).

## Alternativas consideradas

- **SPA-com-PKCE (token no browser).** Descartada: expõe `access_token`/`refresh_token` ao
  front; XSS exfiltra; `localStorage` não é `HttpOnly`. É exatamente o gap que o BFF fecha.
- **Token em cookie `HttpOnly` emitido ao browser, sem sessão server-side.** Descartada:
  ainda envia o JWT ao browser (em cookie) e não reaproveita a sessão Redis nem o relay
  centralizado; mais complexo no refresh.
- **Cada serviço como seu próprio resource server, sem gateway-as-client.** Descartada:
  empurraria o manuseio de token para o front e quebraria o "único ponto de entrada".
