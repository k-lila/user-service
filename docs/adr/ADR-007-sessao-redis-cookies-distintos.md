# ADR-007: Sessão server-side no Redis com cookies distintos por serviço

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** gateway / authorization-server

> ADR retroativo: a decisão já está implementada e em produção no blueprint; este
> registro a formaliza para dar rastreabilidade.

## Contexto

O padrão BFF ([[ADR-002]]) exige que o gateway guarde estado server-side: o
`OAuth2AuthorizedClient` (com o JWT) e o `SecurityContext`. O authorization-server, por sua
vez, também mantém sessão HTTP (login/consent). Duas forças moldam esta decisão:

1. **Escala/persistência:** sessão em memória não escala horizontalmente nem sobrevive a
   restart — incompatível com o blueprint de produção.
2. **Colisão de cookie no salto front-channel:** em dev/Docker, gateway (WebFlux) e
   auth-server (servlet) compartilham `localhost`, e **cookies ignoram a porta**. Se ambos
   usarem o nome de cookie default (`SESSION`), o cookie do auth-server **sobrescreve** o do
   gateway durante o redirect front-channel do OAuth2 → o callback lê a sessão errada →
   `authorization_request_not_found`.

Há ainda uma particularidade do Spring Boot 4.0: a autoconfiguração do Spring Session **não
dispara** apenas pela presença da dependência — exige anotação explícita.

## Decisão

Usar **Spring Session no Redis** com habilitação explícita e **cookies de nome distinto por
serviço**:

- **Gateway** (reativo): `@EnableRedisWebSession` em `gateway/.../config/SecurityConfig.java`;
  cookie de sessão `SESSION` (via `CookieWebSessionIdResolver`), `HttpOnly`, flag `Secure`
  parametrizável (`cookieSecure`, default false p/ dev HTTP).
- **Auth-server** (servlet): `@EnableRedisHttpSession` em
  `authorization-server/.../config/SecurityConfig.java`; cookie renomeado para
  **`AUTHSESSION`** via `DefaultCookieSerializer.setCookieName("AUTHSESSION")`, `HttpOnly`,
  flag `Secure` parametrizável (`app.cookie.secure` via `setUseSecureCookie`, default false p/
  dev HTTP) — **simétrico ao gateway**: o overlay TLS liga ambos via `APP_COOKIE_SECURE=true`.

Os nomes distintos (`SESSION` vs `AUTHSESSION`) evitam a colisão no salto front-channel. Em
produção, domínios separados por serviço também resolveriam, mas os nomes distintos garantem
a correção também no cenário dev/Docker de host único.

Além dos cookies distintos, cada serviço usa um **`redisNamespace` próprio** no Spring Session,
para que o isolamento das sessões no Redis não dependa apenas da unicidade dos session ids:

- **Gateway:** `@EnableRedisWebSession(redisNamespace = "gateway:session")` → chaves sob
  `gateway:session:*`.
- **Auth-server:** `@EnableRedisHttpSession(redisNamespace = "authserver:session")` → chaves
  sob `authserver:session:*`.

O default do Spring Session (`spring:session`) misturaria os dois conjuntos de sessões no mesmo
prefixo; o namespace dedicado torna a separação explícita e facilita operar/segregar cada
conjunto.

Sem mudança de contrato de API.

## Consequências

**Positivas:**
- Sessão **escalável e compartilhável** entre instâncias (estado no Redis), sustentando o
  BFF ([[ADR-002]]) e a escala horizontal.
- Corrige um **bug real** de colisão de cookie (`authorization_request_not_found`) no fluxo
  OAuth2 de host único.
- Habilitação explícita torna a configuração de sessão inequívoca no Spring Boot 4.0.

**Negativas / atenção:**
- Adiciona **dependência do Redis** ao caminho de sessão de gateway e auth-server (já
  presente no stack — Redis Sentinel).
- A anotação explícita (`@EnableRedis*Session`) é um **requisito não-óbvio** do Spring Boot
  4.0: omiti-la faz a sessão cair silenciosamente para o default — armadilha a documentar
  (feito em `CLAUDE.md` §Convenções).
- A flag `Secure` dos cookies (`SESSION` no gateway e `AUTHSESSION` no auth-server) é default
  `false` para dev HTTP puro; em prod **deve** ser ligada (`app.cookie.secure`/borda TLS). Os
  dois serviços honram a mesma env (`APP_COOKIE_SECURE`), sem assimetria.
- **Troca de `redisNamespace` invalida as sessões existentes** no deploy que a introduz: as
  sessões gravadas sob o prefixo antigo (`spring:session:*`) deixam de ser encontradas e todos
  os usuários deslogam **uma vez**. Aceitável para um ajuste de manutenção; comunicar na nota
  de release.

**Testes de regressão:** o fluxo BFF OAuth2 ponta a ponta (que exercita as duas sessões e o
salto front-channel) é coberto por `GatewayOAuth2FlowIntegrationTest`; a sessão do
auth-server, pelos testes de integração do fluxo OAuth2 (Redis real).

## Alternativas consideradas

- **Sessão in-memory (default sem Spring Session).** Descartada: não escala horizontalmente
  nem persiste entre restarts.
- **Mesmo nome de cookie nos dois serviços.** Descartada: é a **causa** da colisão front-
  channel (`authorization_request_not_found`) no cenário de host único.
- **Front stateless (sem sessão server-side).** Descartada: contraria o BFF ([[ADR-002]]) —
  exporia o token ao browser. A sessão server-side é o que permite manter o JWT no backend.
