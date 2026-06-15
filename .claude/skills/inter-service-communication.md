# Skill (referência): Comunicação entre serviços

> Documento de conhecimento lido por `techlead` e `senso-critico`. Reflete **apenas o
> que o projeto usa hoje**: REST síncrono via OpenFeign. Não há mensageria. Para checar
> se uma mudança quebra este contrato, use a skill `/check-compat`.

## REST síncrono (OpenFeign) — o único canal entre serviços hoje

- **Quem fala com quem:** `authorization-server` → `user-service`, via
  `GET /internal/users/email/{email}` (busca credenciais/roles no login).
- **Cliente:** `IUserClient` (`@FeignClient`), com `FeignConfig` injetando o header
  `X-Internal-Token` (shared secret). Sem o header → user-service responde **403**.
- **Resiliência obrigatória:** circuit breaker Resilience4j
  (`spring.cloud.openfeign.circuitbreaker.enabled=true`) + `UserClientFallbackFactory`.
  Indisponibilidade do user-service → fallback retorna `UsernameNotFoundException`
  imediato, em vez de travar em timeout.
- **Timeout** configurado (3s) — nunca chamada Feign sem timeout.

## Borda e relay de token (gateway)

- O gateway é o único ponto de entrada externo. Guarda o `OAuth2AuthorizedClient`
  (com JWT) na sessão Redis e o relaya downstream via **`TokenRelay` por rota** (na
  rota `user-service`) — o SPA nunca vê o JWT (padrão BFF).
- Os serviços internos **nunca** são chamados diretamente em produção.

## Versionamento de API

- Endpoints públicos sob `/v1/` (ex.: `/v1/users/**`). **Nunca quebre um contrato sem
  introduzir nova versão** (`/v2/`). Mudança de contrato exige ADR (`docs/adr/`).

## Fora do escopo atual (evolução futura)

Kafka (eventos de domínio), gRPC e o padrão Saga para transações distribuídas **não
são usados** neste projeto. Se uma tarefa propuser qualquer um deles, trate como
decisão arquitetural nova: exige ADR e revisão do `senso-critico` antes de qualquer código.
