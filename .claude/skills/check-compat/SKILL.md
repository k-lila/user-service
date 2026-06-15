---
name: check-compat
description: Checa se a mudança atual quebra compatibilidade de contrato entre os serviços — Feign↔controller, claims do JWT, schema/cache, rotas do gateway, cookies de sessão. Read-only (não edita). Use antes de fechar uma implementação ou na revisão. Ex: /check-compat ou /check-compat main
arguments: [base-ref]
allowed-tools: Read, Bash, Grep
---

Você vai produzir um **relatório read-only de compatibilidade**. Não edite nenhum
arquivo. Base de conhecimento: `.claude/skills/invariants-and-contracts.md` e
`docs/CONVENCOES.md` — leia-os antes de concluir.

> `base-ref` (opcional, default `HEAD`): a referência contra a qual comparar. Use `HEAD`
> para a árvore de trabalho atual (mudanças não commitadas) ou `main` para o branch inteiro.

## Mudança em análise (diff)

!`BASE="${base_ref:-HEAD}"; echo "### Arquivos alterados vs $BASE"; git diff --stat "$BASE" 2>/dev/null || git diff --stat; echo; echo "### Diff completo"; git diff "$BASE" 2>/dev/null || git diff`

## Superfícies de contrato (estado atual no repo)

### 1. Contrato Feign interno — cliente vs controller
!`echo "--- IUserClient (auth-server, consumidor):"; F=$(grep -rl 'interface IUserClient' --include='*.java' authorization-server/src/main 2>/dev/null | head -1); [ -n "$F" ] && cat "$F" || echo "(não encontrado)"; echo; echo "--- InternalUserController (user-service, provedor):"; F=$(grep -rl 'class InternalUserController' --include='*.java' user-service/src/main 2>/dev/null | head -1); [ -n "$F" ] && cat "$F" || echo "(não encontrado)"`

### 2. Claims do JWT — produtor
!`F=$(grep -rl 'class TokenCustomizerConfig' --include='*.java' authorization-server/src/main 2>/dev/null | head -1); [ -n "$F" ] && cat "$F" || echo "(TokenCustomizerConfig não encontrado)"`

### 3. Entidade/schema e chaves de cache
!`echo "--- Entidade User:"; F=$(grep -rl '@Document' --include='*.java' user-service/src/main 2>/dev/null | head -1); [ -n "$F" ] && cat "$F" || echo "(entidade @Document não encontrada)"; echo; echo "--- Nomes de cache (CacheConfig / @Cacheable):"; grep -rhoE 'usersById|usersByEmail|authByEmail' --include='*.java' user-service/src/main 2>/dev/null | sort -u`

### 4. Rotas do gateway e cookies de sessão
!`echo "--- Rotas (GatewayRouter):"; F=$(grep -rl 'RouteLocator' --include='*.java' gateway/src/main 2>/dev/null | head -1); [ -n "$F" ] && grep -nE 'path\(|route\(|uri\(|TokenRelay' "$F" || echo "(GatewayRouter não encontrado)"; echo; echo "--- Nomes de cookie de sessão:"; grep -rhoE '"SESSION"|"AUTHSESSION"|"XSRF-TOKEN"' --include='*.java' gateway/src/main authorization-server/src/main 2>/dev/null | sort -u`

---

## Tarefa — relatório de compatibilidade

Compare o **diff** com as superfícies de contrato acima e avalie cada item. Para cada um,
classifique: ✅ COMPATÍVEL · ⚠️ ATENÇÃO (compatível, mas requer cuidado/migração) ·
❌ QUEBRA (incompatível — bloqueia).

1. **Feign ↔ controller:** a assinatura consumida por `IUserClient` (path, método, tipo
   de retorno, campos usados do DTO) continua casando com `InternalUserController`? Campo
   removido/renomeado no provedor que o consumidor usa = QUEBRA.
2. **Claims do JWT:** algum claim que consumidores (gateway, controllers via
   `@AuthenticationPrincipal`/`jwt.claim`) dependem foi removido/renomeado/teve tipo alterado?
3. **Contrato público (`/v1/`):** endpoint público mudou assinatura sem nova versão? Status
   HTTP ou formato de erro (`ProblemDetail`) alterado de forma observável?
4. **Schema/cache:** mudança na entidade `User` é retrocompatível com documentos já
   persistidos e com as chaves/DTOs de cache (`usersById`/`usersByEmail`/`authByEmail`)?
5. **Borda/sessão:** rotas do gateway, `TokenRelay` por rota, isenção de CSRF e nomes de
   cookie (`SESSION`/`AUTHSESSION` distintos) preservados?

**Saída:** tabela `{ superfície, classificação, evidência (arquivo:linha), ação }`. Se
houver qualquer ❌, conclua com **"COMPATIBILIDADE: QUEBRA — exige ADR + nova versão ou
revisão do contrato"**. Se só ✅/⚠️, conclua **"COMPATIBILIDADE: OK"** listando as
atenções. Nunca edite arquivos — esta skill só diagnostica.
