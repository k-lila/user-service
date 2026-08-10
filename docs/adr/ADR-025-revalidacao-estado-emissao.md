# ADR-025: Re-derivação do estado do titular na emissão do authorization code

- **Status:** aceita
- **Data:** 2026-08-08
- **Serviço alvo:** authorization-server (principal) · gateway (secundário)
- **Tarefa relacionada:** TASK-REVALIDACAO-EMISSAO (incidente de produção, 2026-08-07)

## Contexto

O `/oauth2/authorize` aceitava a sessão do IdP (cookie `AUTHSESSION`) como prova **completa** de
identidade **e de autoridade**. Com `requireAuthorizationConsent(false)`
(`OAuth2ClientConfig.java:139`) não há sequer tela de consentimento, e o Spring Authorization Server
emite o `authorization_code` **sem chamar** `AuthorizationService.loadUserByUsername`: o
`TokenCustomizerConfig` monta `userID`/`roles`/`permissions` a partir das authorities **congeladas na
sessão no momento do login**, nunca de uma releitura da fonte de verdade.

O mecanismo, em uma frase: **as três checagens da [ADR-017](ADR-017-revogacao-ativa-token.md) comparam
`iat < epoch`, e o token reemitido nasce com `iat = agora`; logo todas aprovam por construção.** A
revogação não falhou — foi **contornada**, porque o crédito foi reemitido limpo. O comentário em
`TokenCustomizerConfig.java:45` (*"fora do refresh, o epoch é irrelevante — o login acabou de validar
o estado"*) era a premissa falsa: **no SSO silencioso não houve login**.

**Evidência do incidente** (fato medido, não inferido):

| Instante (UTC) | Fato |
| --- | --- |
| `18:10:22.094` | `DELETE /v1/users/delete/me`, titular `6a761a6cbb6bdaf7004f8f27` |
| `18:10:22.122` | epoch gravado em `revoke:user:6a761a6cbb6bdaf7004f8f27`; Mongo `users`: **0 documentos** |
| `18:10:25.876` | gateway rejeitou o token antigo (401) e invalidou a sessão do gateway — **a ADR-017 funcionou exatamente uma vez** |
| `18:10:28 → 18:43:24` | Postgres `oauth2_authorization`: **nove** linhas novas, todas `authorization_code`, todas posteriores à eliminação |
| — | `auditLogs`: **zero** entradas após `18:10:23`. Como o único caminho até o canal interno é `loadUserByUsername`, **nenhuma das nove emissões foi um login** |
| `18:43+` | `authserver:session:sessions:ad6b86f2-…` **viva no Redis 33 minutos após a eliminação** |

**O alcance é maior que o sintoma.** O SSO silencioso pula `loadUserByUsername` inteiro, e é lá que
moram três gates: revogação de role (o mais grave — cada autorização **renova** a sessão, então o
privilégio revogado se **auto-renova**), `active=false` (soft delete) e o gate de e-mail verificado
([ADR-015](ADR-015-verificacao-email-cadastro.md)).

Havia ainda um quarto ponto, na borda: o `RevocationWebFilter` do gateway decodificava o access token
da sessão com o decoder do resource server, que valida `exp`. Token expirado →
`JwtValidationException` → `onErrorResume` → **a checagem de revogação era pulada inteira**. Observado
3× em 35 min de uso normal: caminho comum, não borda.

**Conformidade.** O titular exerceu o direito de eliminação e o sistema seguiu gravando o e-mail dele
em `oauth2_authorization` por 33 minutos. A eliminação não era terminal.

## Decisão

### 1. Re-derivação na emissão (`AuthorizationEndpointRevalidationFilter`)

Filtro na chain `@Order(1)`, **depois** do `SecurityContextHolderFilter`, atuando **apenas** no
authorization endpoint. Havendo sessão autenticada, ele re-deriva o titular da fonte de verdade e
compara; divergindo, invalida a sessão.

- **Reuso de `AuthorizationService.loadUserByUsername`, não um caminho Feign paralelo.** Herda sem
  reimplementar: gate de `active`, gate de e-mail com carência (ADR-015) e a distinção
  404-de-negócio × indisponibilidade do `UserClientFallbackFactory`
  ([ADR-021](ADR-021-remocao-listagem-publica-usuarios.md)). Chamar o Feign direto reimplementaria
  três gates e reabriria os bugs que a ADR-021 fechou.
- **Chamada direta ao `UserDetailsService`, nunca via `AuthenticationManager`/`ProviderManager`.**
  Este publicaria `AuthenticationSuccessEvent`, e o `LoginAttemptListener` **zeraria** o contador de
  lockout da [ADR-010](ADR-010-resolucao-ip-cliente-confiavel.md) a cada visita ao authorize —
  **bypass de controle de segurança sem prova de senha**. O mesmo evento re-carimbaria o instante de
  autenticação (ver 3). Um defeito, três controles.
- **A comparação é: existência × `enabled` × authorities. Só.** `accountNonLocked` fica
  **deliberadamente** de fora: se conta bloqueada invalidasse sessão viva, qualquer um derrubaria a
  sessão de outro errando cinco senhas — o lockout viraria DoS.
- **A comparação de authorities inclui a authority `USER_ID:`, não só as `ROLE_*`.** É dela que o
  `TokenCustomizerConfig` (arquivo **não alterado** por esta mudança) tira o claim `userID`. Comparar
  só roles deixaria um **delete + re-registro com o mesmo e-mail e as mesmas roles** cunhar um token
  cujo `userID` aponta para documento inexistente — o bug original por outra porta.
- **Recorte por prefixo (`ROLE_`/`USER_ID:`), e não comparação do conjunto cru.** O
  `DaoAuthenticationProvider` do Spring Security 7 acrescenta ao token do login authorities de
  **fator** (`FACTOR_PASSWORD`) que o `UserDetails` não tem. Comparar conjuntos crus faz **toda**
  sessão divergir de si mesma e invalida **todo** login — a pior regressão possível desta correção.
  Descoberto durante a implementação, por teste de integração; o teste unitário com tokens montados à
  mão não pegava.
- **Divergência invalida; não atualiza em cima.** O filtro não substitui as authorities do
  `SecurityContext` nem emite código com as novas na mesma requisição. O re-login re-deriva o estado
  por definição, pelo caminho já testado.
- **Matcher positivo, derivado de `AuthorizationServerSettings.getAuthorizationEndpoint()`.** Nunca
  do literal `/oauth2/authorize`. Enumerar exclusões é o padrão que produziu o G1; e um
  `.authorizationEndpoint(...)` customizado faria o literal parar de casar **em silêncio** — fix
  inerte com build verde. A chain `@Order(1)` casa **todo** o `endpointsMatcher` do SAS (incl.
  `/oauth2/token` e `/connect/logout` via `oidc()`), então o recorte é o que impede o filtro de atuar
  no back-channel e no logout.
- **Como a sessão inválida vira 302 `/login`:** o filtro invalida, limpa o `SecurityContext` e
  **segue a chain**. O `AuthorizationFilter` nega, o `ExceptionTranslationFilter` salva o request numa
  sessão nova e comissiona o `LoginUrlAuthenticationEntryPoint` já existente. Consequência desejada: o
  `SavedRequestAwareAuthenticationSuccessHandler` retoma o fluxo original após o re-login.
- **Não há risco de laço `/login` ↔ `/oauth2/authorize`**, e a razão é o reuso: para `enabled=false` o
  form login aplica o **mesmo** gate e a `DisabledException` aparece em `/login?error` **antes** de o
  filtro poder ciclar. Registrar isto é o que impede uma futura "otimização" (chamar o Feign direto)
  de reintroduzir o laço.
- **O guard `instanceof UsernamePasswordAuthenticationToken` dos listeners é load-bearing — não o
  relaxe** (`LoginAttemptListener:39,46` e `AuthenticationInstantListener:44`). Medido na FASE 4: o
  próprio SAS autentica o `OAuth2AuthorizationCodeRequestAuthenticationToken` por um `ProviderManager`
  e publica `AuthenticationSuccessEvent` a **cada** authorize — 3 autorizações, 3 eventos —, e isso
  vale **desde antes** desta ADR. Logo, o que protege o lockout do ADR-010 não é a ausência de
  evento: são **duas** coisas, e a primeira versão deste documento nomeava só uma. (1) a re-derivação
  chamar o `UserDetailsService` **direto**, fora do `AuthenticationManager`; e (2) **este guard de
  tipo**, que faz os listeners ignorarem o evento do SAS. Sem (2), o evento já existente hoje zeraria
  o contador de lockout **sem prova de senha** — o principal aninhado é o
  `UsernamePasswordAuthenticationToken` do titular, então `auth.getName()` resolve para o e-mail e
  `loginSucceeded` apaga a chave. Quem ler apenas (1) pode concluir que o guard é redundante no
  caminho de sucesso. Não é.

### 2. Degradação por epoch quando a fonte de verdade está fora

`UserServiceUnavailableException` (ADR-021) **não** propaga como erro e **não** invalida por si só —
um outage não pode derrubar o login de quem já está dentro. O filtro degrada para
`RevocationRefreshGuard.isRevoked(userID, instanteDeAutenticação)`, a única checagem que precisa
apenas do Redis. Redis fora → `false` → **fail-open**, simétrico à ADR-017. O javadoc do guard foi
atualizado para nomear os dois consumidores; o método sempre foi genérico.

### 3. Instante de autenticação carimbado na sessão, e teto de vida ancorado nele

`AuthenticationInstantListener` (mesmo padrão e pacote do `LoginAttemptListener`, mesmo guard de tipo)
grava o instante em `AuthenticationInstantAttribute.NAME`, como **`Long` de epoch millis**.

- **O tipo é requisito, não detalhe.** Precedente da ADR-021: três testes de integração quebraram por
  `SerializationException` ao guardar objeto não-serializável na sessão Redis. Aqui a falha seria
  pior — o carimbo acontece em **todo** login bem-sucedido, então quebraria **todo** login.
- **O teto (`security.session.max-lifetime`, default 8h) mede o instante de AUTENTICAÇÃO, não
  `getCreationTime()`.** No BFF a sessão nasce **antes** do login (CSRF + saved request) — caminho
  normal, não exceção. Ancorar na criação puniria o tempo de sessão **anônima**: uma aba aberta há 8h
  faria o login legítimo nascer já vencido, trocando a correção por um bug de UX.
- **Fallback sem migração:** sessões anteriores ao deploy não têm o atributo e caem em
  `getCreationTime()`. Não lança, não invalida por ausência.
- **A re-derivação nunca re-carimba o instante.** É load-bearing em **dois** lugares: além de esvaziar
  o teto (auto-renovação, o mesmo mecanismo do incidente), derrotaria a degradação do item 2, porque
  `isRevoked` testa `epoch > instante` e um T0 sempre fresco devolveria `false` para sempre — o bug do
  incidente reencarnado no fallback de outage.

**Granularidade declarada, não "teto absoluto".** Quem renova por `refresh_token` **não passa pelo
authorize**, então a granularidade real do teto é ≈ a vida do refresh token: **~60 min** com os
defaults do SAS (`refreshTokenTimeToLive=60m`, `reuseRefreshTokens=true`, hoje não sobrescritos no
repositório). Dizer "absoluto" repetiria a imprecisão de *"revogação força re-autenticação"* na mesma
família de controles. Os dois settings ficam **congelados por teste**, junto dos grant types
(`{authorization_code, refresh_token}`): mudá-los muda a granularidade e passa a exigir extensão da
cobertura.

### 4. Fim do fail-open da borda com token expirado (`RevocationTokenReader`)

O gateway ganha um tipo próprio que **guarda** um `NimbusReactiveJwtDecoder` (JWKS lido de
`security.revocation.jwk-set-uri`, busca preguiçosa) com os validadores substituídos por um que sempre
aprova — verifica **assinatura**, ignora `exp` — e expõe apenas `(userID, issuedAt)`.

**Ele nunca é um bean de `ReactiveJwtDecoder`, sob nenhum qualifier, e isto é o ponto mais perigoso
da leva.** O gateway não declara esse bean: ele vem da autoconfig, que é
`@ConditionalOnMissingBean(ReactiveJwtDecoder.class)`. Declarar o decoder leniente com aquele tipo
**desligaria a autoconfig** e o resource server passaria a aceitar **bearer JWT expirado** — a
correção de segurança *enfraqueceria* o serviço. E `AbstractGatewayIntegrationTest:37` **mocka** o
decoder, então nenhum teste existente pegaria. A garantia é dupla, porque cada metade sozinha falha:
"existe exatamente um bean de `ReactiveJwtDecoder`" **passaria** no cenário catastrófico (o leniente
sendo esse único bean); quem fecha é a asserção **comportamental** de que esse único bean rejeita
`exp` no passado, verificada com JWKS real por WireMock e sem herdar o mock.

Fail-open **permanece** para assinatura inválida, token não-parseável e JWKS inalcançável, com WARN
que nomeia a causa. Ignorar `exp` na leitura **não** é bloquear token expirado: é conseguir responder
de quem ele é.

### 5. Configuração

| Propriedade | Serviço | Default | Papel |
| --- | --- | --- | --- |
| `security.session.max-lifetime` (`SESSION_MAX_LIFETIME`) | auth-server | `8h` | Teto de vida da sessão do IdP |
| `security.session.revalidation.enabled` (`SESSION_REVALIDATION_ENABLED`) | auth-server | `true` | **Reversão operacional**, não configuração suportada |
| `security.revocation.jwk-set-uri` (`REVOCATION_JWK_SET_URI`) | gateway | `${AUTH_ISSUER_URI}/oauth2/jwks` | JWKS do leitor leniente |

As três estão no anchor `x-spring-app-env` do `docker-compose.yml` **e** no `.env.example`. Sem o
anchor a propriedade nasce **documentada e inerte** — foi o que aconteceu com
`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`, que ficou no `.env.example` sem `env_file` e sem
`environment:`, e o deploy rodou a 100% de sampling independentemente do `.env`.

O kill switch existe porque o raio de um defeito no filtro é *"ninguém consegue logar"* (R-01, P0) e o
rollback precisa ser restart, não rebuild (precedente: `TOKEN_REVOCATION_ENABLED`). Em `false` o
sistema **volta ao comportamento vulnerável descrito aqui** — é botão de emergência, não modo de
operação.

## Consequências

### Positivas

- As nove emissões do incidente passam a ser **zero**, e a sessão órfã é destruída no primeiro
  contato em vez de sobreviver 33 minutos. A eliminação (LGPD) volta a ser terminal.
- A janela de `ROLE_ADMIN` revogado deixa de ser auto-renovável indefinidamente e passa a ser **≤ 1
  autorização**. Concessão de role também vale a partir do próximo authorize (via re-login).
- Os gates de `active` e de e-mail verificado (ADR-015) passam a valer **também** no SSO silencioso.
- A checagem de revogação na borda deixa de ser pulada com token expirado (3 ocorrências em 35 min).
- A trilha LGPD ganha rastro das re-derivações **bem-sucedidas** (sessão íntegra,
  `AUTHORITIES_DIVERGED`) — antes o SSO silencioso não gerava entrada alguma.

  **Ressalva, corrigida na FASE 5 (CRIT-SEC-01) — não reintroduza a versão otimista.** Uma redação
  anterior deste bullet afirmava que "a trilha passa a registrar o contato pós-eliminação". **É
  falso**, e justamente para o cenário do incidente: `InternalUserController.java:42-43` chama
  `auditService.recordSystem(...)` **depois** de `getUserByEmail` retornar, e
  `AuthenticationService.java:31-37` lança `DomainEntityNotFound` (→404) para titular inexistente
  **ou inativo**. Logo `NOT_FOUND` — hard-delete e soft-delete — continua gravando **zero** entradas
  em `auditLogs`. O único rastro desses casos é a linha SLF4J de invalidação, que o ADR-011 distingue
  explicitamente da trilha LGPD. Auditar o 404 no canal interno seria mudança de desenho e está fora
  do escopo desta ADR.

### Negativas e custos assumidos

- **Volume no canal interno e na trilha LGPD.** Um **login normal** passa a gerar **2** entradas
  `READ_INTERNAL_CREDENTIAL` (form login + re-derivação no replay do saved request), mais **uma por
  autorização** subsequente — o `InternalUserController` audita por requisição, inclusive em cache
  hit. A retenção de 180d da [ADR-022](ADR-022-higiene-estado-persistente.md) **fica como está**: o
  prazo é decisão de conformidade e não muda com o volume; o que muda é armazenamento, e isso se
  revisa com número medido. Este parágrafo é o dado de partida da próxima revisão de
  `AUDIT_LOG_RETENTION`.
- **Latência no front-channel.** Uma chamada Feign (timeout 3s, circuit breaker) por autorização,
  servida pelo cache `authByEmail` (TTL 5 min). Sob circuito aberto a degradação **não** soma timeout.
- **A re-derivação é tão fresca quanto a eviction de `authByEmail`.** Eviction perdida → o filtro
  re-deriva estado obsoleto e conclui "sessão íntegra": o defeito voltaria em silêncio, com o filtro
  aparentando funcionar. O TTL de 5 min limita a janela, não a fecha. Premissa declarada, coberta por
  teste de cruzamento mutação → eviction → re-derivação.
- **Todos deslogam uma vez no deploy.** Sessões sem o atributo caem em `getCreationTime()`; as com
  mais de 8h são invalidadas no primeiro contato. Aceito e pretendido — mesmo padrão já registrado
  para troca de `redisNamespace`.
- **Falha inesperada de (de)serialização desloga em vez de degradar.** O `catch (Exception)` do
  `AuthorizationService` converte o inesperado em `UsernameNotFoundException`, que aqui cai em
  `NOT_FOUND` → fail-closed. Preferido a um terceiro ramo que adivinharia a causa.
- **Sessão do gateway × sessão do IdP podem dessincronizar.** O filtro mata a do IdP; a do gateway
  pode seguir viva até o `exp`. No caso revogado, o item 4 cobre; o user-service segue autoritativo.

### Trocar o próprio e-mail passa a deslogar o titular (P-01, ratificada pelo humano — opção A)

`RegisterService.updateUser` troca o e-mail e evicta o cache do e-mail **antigo**, mas o
`principal_name` da sessão do IdP continua sendo o antigo. Com a re-derivação, a próxima autorização
procura o e-mail antigo → 404 → sessão invalidada. **Aceito:** trocar o identificador de login *é*
evento de segurança, e deslogar é o comportamento de mercado.

**O custo real, corrigido:** o desligamento é **atrasado e não-determinístico**. A sessão do IdP morre,
mas a sessão do gateway + o access token (5m) + o refresh token (60m) seguem válidos — o titular
trabalha normalmente por até **~1h** e só então cai no formulário. Consequência prática: o item de
backlog do SPA **não pode prometer** correlacionar a mensagem ao ato de trocar o e-mail, porque no
instante em que o titular cai o ato já ficou para trás.

**Variante rejeitada por escrito:** re-derivar por `userID` (disponível na authority `USER_ID:`) em vez
de por e-mail. Exigiria um endpoint interno novo, ampliando a superfície da
[ADR-006](ADR-006-canal-interno-isolado.md), e quebraria o reuso do `UserDetailsService` que é toda a
justificativa do desenho.

### Observabilidade

Toda invalidação emite **uma** linha com o motivo discriminado —
`NOT_FOUND | DISABLED | AUTHORITIES_DIVERGED | MAX_LIFETIME | REVOKED_EPOCH` — em formato pipe, com o
e-mail por `LogUtils.maskEmail` e `traceId`/`spanId` B3. O caminho de fail-open emite WARN
distinguível. Sem isso, a verificação em produção valeria no dia do deploy e deixaria de valer na
semana seguinte: o incidente só foi diagnosticável por perícia manual no Postgres e no Redis.

> **`NOT_FOUND` é ambíguo por desenho, e o registro disto é obrigatório:** tem **duas** causas
> legítimas — titular eliminado **e** titular que trocou o próprio e-mail (P-01). Quem usar essa linha
> como evidência de que a eliminação funcionou precisa cruzá-la com outro sinal.

### Contrato de API

Sem rota nova ou removida; **três comportamentos observáveis mudam** — e é isso que define contrato:

1. `GET /oauth2/authorize` com sessão viva porém **obsoleta** deixa de responder `302 …?code=…` e
   passa a responder `302 /login`, com o `AUTHSESSION` invalidado. Para o gateway isso é
   indistinguível de "sessão do IdP expirada", caso que o fluxo já trata. Ampliação do conjunto de
   condições que levam ao login, não quebra de formato.
2. Gateway: **401 novo** onde antes havia pass-through, com token **expirado** na sessão e titular
   revogado. Mesma classe aditiva que a ADR-017 registrou.
3. Três propriedades novas, com default seguro.

O canal interno `/internal/users/email/{email}` (ADR-006) **não muda** — nem path, nem payload
(`AuthDTO`), nem `X-Internal-Token`. Muda a **frequência**, e isso é deliberado.

### Notas de atualização em ADRs anteriores

- **ADR-017 § Decisão 5** dizia que *"revogação força re-autenticação"*. **Não forçava** — forçava
  *reemissão*, e a reemissão **lavava** o token. A nota de atualização lá aponta para cá, com o texto
  original preservado.
- **ADR-022:** a purga de `oauth2_authorization` continua sendo a rede que expira as linhas criadas
  **antes** da eliminação; o que muda é que linhas espúrias deixam de nascer. A aritmética da retenção
  de `auditLogs` muda conforme registrado acima.

## Alternativas consideradas

| Alternativa | Por que foi descartada |
| --- | --- |
| **Atualizar as authorities da sessão in-place** e emitir com as novas | Emitir credencial a partir de um estado que acabou de mudar, na mesma requisição, é reintroduzir a premissa do defeito por outra via. Invalidar delega ao re-login, caminho já testado, com um único conceito de "onde o estado é derivado". |
| **Eliminação push** (canal interno para o auth-server apagar sessões/registros de um titular) | `@EnableRedisHttpSession` entrega o repositório **não-indexado**, que não busca sessão por principal; exigiria o indexado + keyspace notifications = mudança de infraestrutura de sessão. E o mérito cai muito com a re-derivação: a sessão órfã fica **inerte** e morre no primeiro contato. **ADR própria, futura.** |
| **Re-derivar por `userID`** em vez de por e-mail | Endpoint interno novo (superfície da ADR-006) e perda do reuso do `UserDetailsService`. Rejeitada — ver P-01. |
| **Decoder leniente como bean de `ReactiveJwtDecoder`**, administrado por qualifier | Desliga a autoconfig por `@ConditionalOnMissingBean` e o gateway aceita bearer expirado; injeção ambígua; nenhum teste atual pegaria. Encapsular elimina a categoria inteira do problema em vez de administrá-la. |
| **Teto ancorado em `getCreationTime()`** | Puniria o tempo de sessão anônima (CSRF + saved request), que é o caminho normal do BFF: login legítimo nascendo vencido. |
| **Re-derivar via `AuthenticationManager`** (reaproveitando o `ProviderManager`) | Publicaria `AuthenticationSuccessEvent`: **zeraria o lockout** sem prova de senha e re-carimbaria o instante de autenticação. Um defeito, três controles. |
| **Introspection por-request ou denylist de `jti`** | Já descartadas na ADR-017 e não reabertas aqui: nenhuma cobre "revogar todos os tokens de um usuário". |
