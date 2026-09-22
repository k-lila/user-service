# ADR-NNN: Título da decisão

> Copie este arquivo para `docs/adr/ADR-NNN-titulo-kebab-case.md` (numeração sequencial).
> Criado pelo `techlead` sempre que a tarefa envolver: novo endpoint ou alteração de
> contrato de API, mudança de schema (MongoDB/PostgreSQL), nova dependência entre
> serviços, ou escolha de padrão de resiliência (circuit breaker, retry, timeout).

- **Status:** proposta | aceita | substituída por ADR-NNN | obsoleta
- **Data:** AAAA-MM-DD
- **Serviço alvo:** {gateway | authorization-server | user-service | config-server | discovery-server | login-interface}
- **Tarefa relacionada:** TASK-NNN / C\<n\> / G\<n\>

## Contexto

Qual problema ou força motriz levou a esta decisão. Inclua restrições do projeto
(separação rígida de responsabilidades, auth-server não acessa MongoDB, roles fixas
USER/ADMIN, etc.) quando relevantes.

## Decisão

O que foi decidido, em termos concretos. Se altera contrato de API, registre o
versionamento (`/v1/`, `/v2/`) e a compatibilidade retroativa.

## Consequências

Impactos positivos e negativos. Serviços consumidores afetados. Necessidade de
testes de regressão. Novos pontos de observabilidade.

## Propagação

O que atualizar **no mesmo commit** desta ADR. Uma decisão que não chega aos documentos
estruturais é invisível para quem vai mexer no código — foi assim que a ADR-026 ficou fora do
mapa `§9`, a única das 26 nessa situação, e o G14 viveu semanas só na memória dos agentes.

- [ ] `docs/ARQUITETURA.md § 9` — mapa ADR → componente. **Sempre**: é o índice invertido que
      responde "quais decisões governam este arquivo?"
- [ ] `docs/ARQUITETURA.md § 6.x` — se a decisão altera um fluxo ponta a ponta
- [ ] `docs/SERVICOS.md` — se muda endpoint, schema, claim do JWT ou chave de cache
- [ ] `docs/CONVENCOES.md` — se toca a tabela das sete cópias do estado de autorização
- [ ] `docs/SECURITY.md` — se muda um **controle ativo**. Só estado corrente: a narrativa do
      fechamento fica nesta ADR, não lá
- [ ] `CLAUDE.md` — apenas se muda uma invariante do mapa

## Alternativas consideradas

O que foi descartado e por quê.
