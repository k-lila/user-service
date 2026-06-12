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

## Alternativas consideradas

O que foi descartado e por quê.
