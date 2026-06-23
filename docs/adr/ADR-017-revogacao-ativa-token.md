# ADR-017: Revogação ativa de token (epoch de revogação por usuário)

- **Status:** aceita
- **Data:** 2026-06-22
- **Serviços alvo:** user-service, authorization-server, gateway
- **Tarefa relacionada:** gap "Ausência de revogação ativa de token (pós-revogação de role ou
  desativação de conta)" (`docs/SECURITY.md`, tabela de dívida aceita)

## Contexto

Os resource servers (gateway e user-service) validavam o access token só por **assinatura + `exp`**,
sem introspection/blocklist. Logo, após `PATCH /v1/admin/users/{id}/roles` (revogação de role) ou
desativação/hard-delete de conta, um access token **já emitido** continuava aceito — roles antigas
seguiam valendo e uma conta desativada continuava operando com o token em mãos. As evictions de cache
(`authByEmail`) só afetavam o **próximo** token.

O `docs/SECURITY.md` registrava a janela como "≈ TTL do access token (~5 min)". A análise desta ADR
mostrou que isso era **otimista**: o gateway (BFF) renova o token silenciosamente via grant
`refresh_token`, e o Spring Authorization Server, no refresh, **reusa as authorities armazenadas** (não
recarrega roles/`active` do user-service). Um usuário revogado/desativado recebia, então, access tokens
válidos **indefinidamente** até logout/re-login. Fechar **só** o token vivo seria derrotado pelo
refresh — o caminho do refresh precisava ser fechado junto.

Restrições observadas: roles fixas `USER`/`ADMIN`; autorização por role é sempre *downstream*
(`@PreAuthorize`); separação rígida (o auth-server não acessa MongoDB — só Feign); Redis Sentinel já é
infra compartilhada (sessão/lockout/cache/rate limit); BFF/token fora do browser (ADR-002) preservado.

## Decisão

Revogação ativa via **epoch de revogação por usuário** ("not-valid-before") — um sinal único no Redis
compartilhado pelos três serviços, sem introspection por requisição nem novo canal Feign:

1. **Escrita (user-service).** `TokenRevocationService` grava `revoke:user:{userID}` = instante atual
   (millis), TTL `security.revocation.ttl` (default `75m`, ≥ vida do refresh token — a marca se
   auto-limpa). Chamado, junto das evictions de cache já existentes, em: `AdminService.updateUserRoles`
   (grant/revoke de role), `RegisterService.deactivateUser` (soft-delete self **e** admin) e
   `RegisterService.deleteUser` (hard-delete self **e** admin).

2. **Token vivo (resource servers).** Rejeitam o token cujo `iat` precede o epoch do titular:
   - **user-service** (servlet): `RevocationTokenValidator` (`OAuth2TokenValidator<Jwt>`) somado aos
     validadores default (issuer/exp) via `DelegatingOAuth2TokenValidator` num `JwtDecoder` com
     resolução OIDC **preguiçosa** (preserva a independência de startup do auth-server).
   - **gateway** (reativo, BFF por sessão): `RevocationWebFilter` (`GlobalFilter`) inspeciona o access
     token guardado na sessão (o JWT relayado), decodifica `userID`/`iat`, consulta o Redis e responde
     **401** + invalida a sessão. Defesa em profundidade — o user-service é a camada autoritativa.

3. **Caminho do refresh (authorization-server).** `RevocationRefreshGuard` lê o mesmo epoch; no grant
   `refresh_token`, `TokenCustomizerConfig` aborta a reemissão (`OAuth2AuthenticationException` /
   `invalid_grant`) quando a revogação é mais recente que a emissão do refresh token apresentado. O
   refresh falha → o gateway não renova silenciosamente → o usuário cai no login, onde o gate (ADR-015)
   re-deriva roles e bloqueia conta inativa. Fora do refresh (authorization_code) o epoch é irrelevante.

4. **Fail-open** (decisão consciente — disponibilidade sobre rigor): erro de Redis na escrita não
   derruba a operação; na leitura, é tratado como "não-revogado". Um outage de Redis não bloqueia a
   autenticação (o login já depende do Redis de qualquer forma). Toggle único `security.revocation.enabled`.

5. **Invariante:** revogação **força re-autenticação** (não muta a sessão viva) — o re-login re-deriva o
   estado correto. O `key-prefix` (`revoke:user:`) deve casar entre os três serviços.

Sem mudança de schema (MongoDB/Postgres) e sem alteração de rota no gateway (a marca vive no Redis).

## Consequências

- **Positivo:** fecha o gap — revogar role / desativar / remover invalida ativamente os tokens já
  emitidos (≈ segundos via resource servers) e impede o refresh de reemitir credenciais válidas. Janela
  residual cai de "indefinida (via refresh)" para ≈ segundos no token vivo (e ≤ TTL do access token até
  a primeira request pós-revogação).
- **Custo:** uma leitura Redis por requisição autenticada em cada resource server (gateway + user-service)
  e uma no refresh do auth-server — barato (GET por chave), no caminho quente. Mitigado por fail-open.
- **Contrato:** aditivo. Surge um **401** novo (token revogado) onde antes o token seria aceito; o
  `refresh_token` de um titular revogado passa a falhar (`invalid_grant`). Nenhum claim do JWT muda; o
  canal interno (ADR-006) e os cookies de sessão (ADR-007) seguem intactos. O front (BFF) já trata 401
  redirecionando ao login — sem mudança no SPA.
- **Observabilidade:** logs INFO na escrita do epoch e na rejeição (resource servers / refresh); WARN nos
  caminhos de fail-open.
- **Dívida residual:** (a) fail-open significa que, durante um outage de Redis, uma revogação recente
  pode não ser aplicada; (b) a revogação força re-login completo (não há downgrade de roles em sessão
  viva) — aceito por simplicidade e segurança; (c) precisão de segundos do `iat` faz um token emitido no
  *mesmo segundo* da revogação ser rejeitado (sentido seguro; re-login interativo leva > 1s).

## Alternativas consideradas

- **OAuth2 token introspection (RFC 7662) por requisição:** descartada — latência e acoplamento por
  request ao auth-server, que ainda não conheceria a mudança de role feita no user-service; um JWT
  self-contained volta `active=true` na introspecção a menos que a autorização seja invalidada.
- **Denylist por `jti`:** descartada — não cobre "revogar todos os tokens de um usuário após mudança de
  role" (os `jti` em aberto não são enumeráveis); serve só para revogar um token específico.
- **Só encurtar o TTL do access token (sem checagem ativa):** descartada — mitiga, não fecha; conta
  desativada/role antiga seguiriam válidas por até o TTL e o refresh continuaria reemitindo.
- **Invalidar as `OAuth2Authorization` no Postgres via novo canal Feign user-service → auth-server:**
  descartada como mecanismo primário — exige um canal interno reverso (novo filtro/segredo no
  auth-server, tocando ADR-006); o epoch no Redis compartilhado entrega o mesmo efeito sem novo contrato.
- **Invalidar a sessão do gateway por índice de principal (Spring Session):** descartada — o principal
  da sessão do gateway é o `sub` do OIDC (não o `userID`), e indexar/cruzar o namespace de sessão do
  gateway a partir do user-service acopla os serviços indevidamente.
- **Fail-closed na indisponibilidade do Redis:** descartada — transformaria um outage de Redis em DoS de
  toda a autenticação; escolhido fail-open, simétrico ao restante do uso de Redis no ecossistema.
