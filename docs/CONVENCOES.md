# Convenções e Invariantes de Design

Este documento detalha as **convenções e invariantes** que sustentam o funcionamento correto
do ecossistema. São decisões já tomadas e estabilizadas na v1 do blueprint — ao manter ou
evoluir o sistema, **preserve estas invariantes**: quebrá-las reintroduz bugs já resolvidos
(muitos custaram diagnóstico não-trivial). As decisões arquiteturais formais têm ADR próprio em
[`docs/adr/`](adr/); aqui ficam as convenções operacionais e seus _porquês_.

## Separação rígida de responsabilidades

- O **authorization-server não acessa o MongoDB** — obtém dados de usuário **apenas via Feign**
  para o user-service (`GET /internal/users/email/{email}`). O domínio de usuário pertence a um
  único serviço; o auth-server é cliente, nunca dono dos dados.

## Endpoints internos isolados (ADR-006)

Dois canais internos existem hoje: `/internal/users/email/{email}` (auth-server → user-service) e
`/internal/notifications/email-verification` (user-service → notification-service, ADR-015).

- **Nenhum está no gateway nem no Swagger.**
- Protegidos pelo shared secret **`X-Internal-Token`**: `InternalTokenFilter` valida em ambos os
  serviços receptores (comparação em tempo constante com `MessageDigest.isEqual`); `FeignConfig`
  injeta no chamador. Acesso direto às portas 8090/8095 **sem o header → 403**.
- **Invariante:** nunca exponha esses endpoints pelo gateway nem documente no Swagger; eles
  presumem rede interna confiável + o shared secret.

**Como cumprir a parte "nem no Swagger" — depende de o serviço publicar OpenAPI (ADR-021):**

| Situação | Mecanismo | Exemplo |
| --- | --- | --- |
| O serviço **publica** doc (o gateway agrega seu `/v3/api-docs`) | anotar a rota interna com `@Hidden` | `InternalUserController` (user-service) |
| O serviço **não publica** doc | **não ter a dependência springdoc no `pom.xml`** | `NotificationController` (notification-service) |

> **Desligar por propriedade não basta** — foi assim que a invariante ficou violada até 2026-08-04.
> O `notification-service` tinha `springdoc.swagger-ui.enabled: false` no YAML servido, mas
> `/v3/api-docs` continuava ativo e publicando a especificação do canal interno. E mesmo
> `springdoc.api-docs.enabled: false` seria garantia **condicional**: a config vem do config-server
> com `optional:configserver:` e evapora se ele estiver fora no boot. Ausência de dependência é
> garantia de classpath — não depende de nada em runtime.

## DELETE com semânticas distintas e intencionais (ADR-001, ADR-013)

| Rota                          | Papel | Efeito                                         |
| ----------------------------- | ----- | ----------------------------------------------- |
| `DELETE /v1/users/remove/me`  | USER  | soft-delete (`deactivateUser`, `active=false`)  |
| `DELETE /v1/users/delete/me`  | USER  | hard-delete (`deleteUser`)                      |

- **Invariante:** as duas rotas são distintas de propósito — o soft-delete preserva o registro
  (e os endpoints de leitura da superfície pública retornam só ativos, ADR-001: hoje
  `searchById`/`searchByEmail`, já que a listagem pública foi removida pelo ADR-021). A listagem
  administrativa `GET /v1/admin/users` **inclui** inativos de propósito — a ocultação sempre foi
  regra da superfície pública, não da administrativa. Não unifique nem troque a semântica sem ADR.
- **Operações administrativas removidas deste controller (ADR-013):** as rotas
  `DELETE /v1/users/{id}` (soft-delete ADMIN sobre outro titular) e `DELETE /v1/users/del/{id}`
  (hard-delete ADMIN) foram retiradas do `UserController`. Foram absorvidas pelo `AdminController`
  dedicado (`/v1/admin/**`, ADR-014), que reativa os valores `SOFT_DELETE_ADMIN`/`HARD_DELETE_ADMIN`
  do enum `AuditAction` (antes reservados, sem rota ativa).

## As sete cópias do estado de autorização (ADR-025)

**O epoch de revogação por usuário (`revoke:user:{userID}` no Redis) é a fonte ÚNICA**, com
`key-prefix` **idêntico** nos três serviços: o user-service grava; gateway, user-service e
auth-server leem. A checagem é **fail-open** — outage de Redis não bloqueia a autenticação. Não
troque por introspection por-request nem por denylist de `jti`: nenhuma das duas cobre "revogar
todos os tokens de um usuário", que é o caso de uso.

> **Autocorreção registrada (2026-08-08).** A revogação, **sozinha, não forçava re-autenticação** —
> ao contrário do que a documentação deste projeto afirmou até essa data. Enquanto a sessão do IdP
> vivesse, o `/oauth2/authorize` reemitia credencial nova com `iat = agora`, e as três checagens
> (`iat < epoch`) aprovavam **por construção**. Quem força a re-autenticação de fato é a
> **re-derivação na emissão** (ADR-025). O registro fica aqui porque a afirmação errada sobreviveu
> meses sem ser contestada, e apagá-la em silêncio perderia a lição.

O estado de autorização de um titular existe **hoje em sete lugares**. Cada um precisa de um dono e de
um mecanismo de invalidação **declarados** — a ADR-025 nasceu porque uma delas (a sessão do IdP) não
tinha nenhum, e sozinha refabricava credencial nova e limpa a partir de estado obsoleto: nove
`authorization_code` emitidos após um hard-delete, com zero autenticações.

| # | Cópia | Dono | Mecanismo de invalidação |
| --- | --- | --- | --- |
| 1 | MongoDB `users` | user-service | **Fonte de verdade** — nada a invalidar |
| 2 | Cache Redis `usersById`/`usersByEmail`/`authByEmail` | user-service | Evict explícito nas mutações + TTL 5 min |
| 3 | **Sessão do IdP** (`AUTHSESSION`, `authserver:session:sessions:*`) | auth-server | Re-derivação na emissão + teto de vida (**ADR-025**) — antes: **NENHUM** |
| 4 | Claims do access token | auth-server emite; gateway/user-service checam | Epoch de revogação (ADR-017; escrito também na troca de senha/e-mail — ADR-026) |
| 5 | Refresh token | auth-server | `RevocationRefreshGuard` (ADR-017 + ADR-026) |
| 6 | PostgreSQL `oauth2_authorization` | auth-server | Só purga por expiração (ADR-022) |
| 7 | Sessão do gateway (`SESSION`, `gateway:session`) | gateway | `RevocationWebFilter` (ADR-017 + correção do `exp`, ADR-025) |

**Lacunas conhecidas** (listadas de propósito — um inventário que só mostra o que **está** coberto não
impede a oitava cópia de entrar, e impede menos ainda que a lacuna já existente seja esquecida):

- ~~**Troca de senha não invalida nada.**~~ **Fechada em 2026-08-10 pela
  [ADR-026](adr/ADR-026-revogacao-troca-senha-email.md).** `RegisterService.updateUser`
  grava o epoch de revogação quando a senha **ou** o e-mail muda, derrubando as cópias #4 e #5 — e,
  por consequência, a #7 no `RevocationWebFilter` e a #3 na re-derivação da ADR-025. Trocar só o nome
  não revoga. O autor da troca também é deslogado (o epoch é por titular, não por sessão).
- **Eliminação push ausente:** não há canal para o auth-server apagar sessões/registros de um titular
  sob demanda; a ADR-025 reduz o resíduo (a sessão órfã fica inerte e morre no primeiro contato), não
  o zera. ADR própria, futura.

> **Invariante:** introduzir uma **oitava** cópia de estado de autorização exige **declarar seu
> mecanismo de invalidação** nesta tabela, no mesmo commit. Esta correção foi o **quarto remendo da
> mesma família** (ADR-017 cobriu #4 e #5, o filtro de borda cobriu #7, a ADR-025 cobriu #3); a tabela
> existe para que não haja um quinto pela mesma razão.
>
> A [ADR-026](adr/ADR-026-revogacao-troca-senha-email.md) **não** é esse quinto remendo, e a distinção
> importa: ela não descobriu uma cópia sem dono, fechou uma lacuna que esta tabela já declarava por
> escrito. É a tabela funcionando como pretendido — o gap foi encontrado por leitura do inventário,
> não por incidente.

## Credenciais e roles

- **BCrypt** com custo padrão (10) para o hash de senha.
- **Roles fixas:** apenas `USER` e `ADMIN` (strings simples no MongoDB) — **sem roles
  dinâmicas**. Mudar isso é mudança de contrato → exige ADR.

## Configuração centralizada

- Configuração não-sensível vem do **config-server** (`classpath:/config`); **segredos vêm de
  Docker secrets** montados em `/run/secrets/` e resolvidos pelo `configtree:` do
  `SPRING_CONFIG_IMPORT` ([ADR-009](adr/ADR-009-base-secrets-native-docker-secrets.md)) — nunca
  do `.env`. Segredos hardcoded são **gaps conhecidos** (ver [docs/SECURITY.md](SECURITY.md)),
  não o padrão — não introduza novos.

## Estado local tira o serviço do eixo replicável (ADR-024)

Os quatro serviços de domínio pertencem ao **eixo replicável** (`--scale <svc>=N`) porque nenhum
deles guarda estado no processo: cache no Redis (`RedisCacheManager`, nunca Caffeine), sessão no
Redis, estado OAuth no Postgres, e os dois `@Scheduled` — `OutboxRetryService` (user-service) e
`OAuthStatePurgeService` (auth-server) — protegidos por lock `SETNX` fail-closed; sem ele, N
réplicas mandam N e-mails (ou N `DELETE` concorrentes) por ciclo.

Isso se perde em silêncio: nada no build acusa, e uma réplica só revela o problema em produção.
Ao introduzir código novo em qualquer um dos quatro, verifique que você **não** acrescentou:

- `ConcurrentHashMap`, `Caffeine` ou `static Map` como estado em bean de serviço;
- `@Scheduled` sem lock distribuído;
- `@PostConstruct` ou `CommandLineRunner` que escreva no banco.

Qualquer um dos três tira o serviço do eixo replicável. Se for inevitável, o eixo do componente
muda e isso precisa ser declarado no ADR-024.

**A exceção existente, e por que ela é segura:** o seed do `gateway-client` em
`OAuth2ClientConfig` escreve no Postgres no boot, com N réplicas subindo juntas. Ele é
check-then-act e a corrida o atravessa — quem impede a duplicata é o **índice único sobre
`client_id`**, e o seed absorve a violação e relê o registro
([ADR-022](adr/ADR-022-higiene-estado-persistente.md)). Escrita no boot só é aceitável com uma
garantia desse tipo **no banco**, nunca no código.

## Cookies de sessão distintos por serviço (ADR-007)

- Gateway usa cookie **`SESSION`**; auth-server usa **`AUTHSESSION`** (via `CookieSerializer`).
- **Por quê:** em dev/Docker ambos compartilham `localhost` e os cookies **ignoram a porta**.
  Com o mesmo nome, o cookie do auth-server **sobrescreveria** o do gateway no salto
  front-channel → o callback leria a sessão errada → `authorization_request_not_found`.
- Nomes distintos evitam a colisão (em prod, domínios separados também resolveriam).
- **Invariante:** mantenha nomes de cookie de sessão distintos entre gateway e auth-server.
- **Namespace Redis distinto:** cada serviço também grava as sessões sob um `redisNamespace`
  próprio — gateway `gateway:session` (`@EnableRedisWebSession(redisNamespace = ...)`) e
  auth-server `authserver:session` (`@EnableRedisHttpSession(redisNamespace = ...)`) — em vez
  do default comum `spring:session`. O isolamento deixa de depender só da unicidade dos session
  ids. **Atenção:** trocar o namespace invalida as sessões existentes (todos deslogam uma vez).

## Spring Session exige habilitação explícita no Spring Boot 4.0

- `@EnableRedisWebSession` (gateway, reativo) e `@EnableRedisHttpSession` (auth-server, servlet).
- A autoconfiguração **não dispara** só pela presença da dependência no Boot 4.0 — a anotação é
  obrigatória. Remover a anotação quebra silenciosamente a sessão no Redis.

## Arquivos de configuração mutáveis em containers

Tanto o **Redis Sentinel** quanto o **MongoDB** precisam **gravar no arquivo de config em
runtime**, o que impede o mount simples com `:ro`.

- **Redis Sentinel** regrava o arquivo para persistir estado (master atual, sentinel IDs).
  Fix: `command: sh -c "cp /etc/redis/sentinel.conf /tmp/sentinel.conf && exec redis-sentinel
  /tmp/sentinel.conf"` — copia o mount `:ro` para `/tmp` gravável antes de iniciar.
- **MongoDB keyfile** precisa de `chmod 400` e `chown 999` antes que o `mongod` o leia. Fix:
  override de **`entrypoint:`** (não `command:`) que copia para `/tmp/mongo.key`, ajusta
  permissões e chama `exec docker-entrypoint.sh mongod`. Usar `command:` em vez de `entrypoint:`
  contornaria o `docker-entrypoint.sh` original e `MONGO_INITDB_ROOT_USERNAME` nunca seria
  processado.
- **Invariante:** ao mexer nesses serviços no compose, mantenha o padrão copy-to-/tmp e o
  override de `entrypoint` do Mongo.

## Skills com caminhos relativos à raiz do projeto

Os blocos de execução `` !`…` `` das skills em `.claude/skills/` rodam a partir da **raiz
do repositório** (diretório onde o Claude Code é iniciado). Por isso devem referenciar
arquivos por **caminho relativo** (ex.: `user-service/src/main`, `docs/adr/TEMPLATE.md`),
**nunca** por caminho absoluto da máquina do autor (ex.: `/home/<user>/.../user-service`).

- **Por quê:** path absoluto quebra a skill em qualquer clone/máquina diferente, enquanto
  o caminho relativo funciona em todos. Um prefixo `cd /home/.../user-service && …` é
  redundante — a skill já executa na raiz.
- **Invariante:** ao criar ou editar uma skill, use caminhos relativos. Checagem rápida:
  `grep -rn "/home/" .claude/skills/` deve retornar vazio.

## Campos novos no entity `User`: nullable + scaffold antes do fluxo completo

- Ao adicionar um campo novo ao entity `User` (MongoDB) antes de implementar o fluxo de
  negócio completo que o consome, o padrão é: **sem `@NotNull`**, documentado como
  `nullable: true` no `@Schema`, e a leitura trata `null` como o valor "neutro" esperado —
  nunca lança nem força backfill em massa dos documentos legados.
- **Exemplos já seguindo o padrão:** `consentAcceptedAt`/`termsVersion` (ADR-012, nullable
  para usuários cadastrados antes do consentimento LGPD); `tenantIds` (reservado para
  multi-tenant futuro, sempre `null` até existir atribuição de tenant).
- **Exemplo que saiu do estado scaffold (ADR-015):** `emailVerified`/`emailVerifiedAt`
  eram scaffold sem fluxo de verificação (`registerUser` sempre setava `true`). Com o
  notification-service, `registerUser` passou a setar `emailVerified=false` no cadastro e
  o campo só vira `true` após a confirmação via `GET /v1/users/verify-email`. O tratamento
  de `null` (registros legados, anteriores ao campo) **não mudou** — continua tratado como
  verificado em toda leitura, nunca como bloqueio de login. Isso ilustra o ciclo de vida
  completo do padrão: nasce nullable/scaffold sem migração, e quando a feature real chega,
  só os registros **novos** passam a ter o valor real — os legados continuam no estado
  neutro original, sem backfill.
- **Por quê:** evita migração de dados obrigatória no deploy do campo e mantém o
  `registerUser`/leituras existentes funcionando sem alteração de comportamento até o dia em
  que a feature que consome o campo for implementada de fato.
- **Invariante:** ao introduzir um campo nessa categoria, replique o comentário explicando o
  default/nullable no entity (`User.java`) e no `UserResponseDTO`, não deixe a intenção
  implícita no código.

## Referências cruzadas (ADRs)

- **ADR-001** — leitura somente de ativos (soft-delete)
- **ADR-002** — padrão BFF
- **ADR-003** — estado OAuth em PostgreSQL
- **ADR-004** — resiliência Feign (circuit breaker)
- **ADR-005** — chave JWK persistente
- **ADR-006** — canal interno isolado
- **ADR-007** — sessão Redis + cookies distintos
- **ADR-009** — base secrets-native (Docker secrets)
- **ADR-012** — consentimento LGPD no cadastro
- **ADR-013** — remoção das rotas admin de DELETE do `UserController`
- **ADR-015** — verificação de e-mail no cadastro
- **ADR-017** — revogação ativa de token
- **ADR-021** — remoção da listagem pública de usuários
- **ADR-022** — higiene do estado persistente
- **ADR-024** — elasticidade, piso mínimo e eixos de escala
- **ADR-025** — re-derivação do estado do titular na emissão
- **ADR-026** — revogação na troca de senha ou e-mail
