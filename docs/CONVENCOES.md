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

## Endpoint interno isolado (ADR-006)

- `/internal/users/email/{email}` **não está no gateway nem no Swagger** — é o canal exclusivo
  auth-server ↔ user-service.
- Protegido por shared secret **`X-Internal-Token`**: `InternalTokenFilter` valida no
  user-service; `FeignConfig` injeta no auth-server. Acesso direto à porta 8090 **sem o header
  → 403**.
- **Invariante:** nunca exponha esse endpoint pelo gateway nem documente no Swagger; ele
  presume rede interna confiável + o shared secret.

## DELETE com semânticas distintas e intencionais (ADR-001, ADR-013)

| Rota                          | Papel | Efeito                                         |
| ----------------------------- | ----- | ----------------------------------------------- |
| `DELETE /v1/users/remove/me`  | USER  | soft-delete (`deactivateUser`, `active=false`)  |
| `DELETE /v1/users/delete/me`  | USER  | hard-delete (`deleteUser`)                      |

- **Invariante:** as duas rotas são distintas de propósito — o soft-delete preserva o registro
  (e os endpoints de leitura retornam só ativos, ADR-001). Não unifique nem troque a semântica
  sem ADR.
- **Operações administrativas removidas deste controller (ADR-013):** as rotas
  `DELETE /v1/users/{id}` (soft-delete ADMIN sobre outro titular) e `DELETE /v1/users/del/{id}`
  (hard-delete ADMIN) foram retiradas do `UserController`. Foram absorvidas pelo `AdminController`
  dedicado (`/v1/admin/**`, ADR-014), que reativa os valores `SOFT_DELETE_ADMIN`/`HARD_DELETE_ADMIN`
  do enum `AuditAction` (antes reservados, sem rota ativa).

## Credenciais e roles

- **BCrypt** com custo padrão (10) para o hash de senha.
- **Roles fixas:** apenas `USER` e `ADMIN` (strings simples no MongoDB) — **sem roles
  dinâmicas**. Mudar isso é mudança de contrato → exige ADR.

## Configuração centralizada

- Segredos vêm do **config-server via env**. Segredos hardcoded são **gaps conhecidos**
  (ver [docs/SECURITY.md](SECURITY.md)), não o padrão — não introduza novos.

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
  multi-tenant futuro, sempre `null` até existir atribuição de tenant); `emailVerified`/
  `emailVerifiedAt` (scaffold sem fluxo de verificação de e-mail — `registerUser` sempre
  seta `true`; `null` em registros legados é tratado como verificado em toda leitura, nunca
  como bloqueio de login).
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
