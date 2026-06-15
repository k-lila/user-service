# Skill (referência): Estratégia de testes

> Documento de conhecimento lido por `qa-tester` e `senso-critico`. Resume `docs/TESTES.md`
> — **a fonte de verdade**. Para gerar testes de uma classe, use a skill invocável
> `/suggest-tests <Classe>`. **Não há Pact/contract-testing neste projeto.** A
> **regressão da suíte existente é a rede que sustenta cada nova entrega** — nunca pule.

## Pirâmide (stack real)

| Camada | Ferramenta | Quando |
|--------|-----------|--------|
| Unitário | JUnit 5 + Mockito puro | Lógica de negócio isolada. `@Cacheable`/`@Transactional` são **ignorados** aqui |
| Controller | `@WebMvcTest` + `@Import(...)` + JWT simulado | Status HTTP, autorização (ROLE_USER/ADMIN), claims, serialização |
| Integração | `@SpringBootTest` + Testcontainers | MongoDB+Redis (user-service); Postgres+Redis+WireMock+fluxo OAuth2 (auth-server) |

Composição atual (ver `docs/TESTES.md` para a contagem vigente): unitários Mockito +
controller `@WebMvcTest` + integração Testcontainers.

## Padrões obrigatórios

### Controller (`@WebMvcTest`)
- `@WebMvcTest(XController.class)` + `@Import({SecurityConfig.class, GlobalExceptionHandler.class})`.
  No Spring Boot 4.0 o slice **não** carrega essas classes sozinho — o `@Import` é mandatório.
- Auth: `SecurityMockMvcRequestPostProcessors.jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))`.
- Claims: `.jwt(jwt -> jwt.claim("userID", "id-1"))`.

### Integração e cache
- Todo teste de integração estende `AbstractIntegrationTest`.
- **Cache só é populado na 2ª chamada** — para verificar população, chame o método
  duas vezes (1ª = miss + popula; 2ª = hit) e então `assertNotNull(cache.get(key))`.
- O `put` do RedisCache fica visível ~ms depois — **use Awaitility**, não
  read-after-write direto (é flaky).
- Evicção: chamar após mutação e `assertNull(cache.get(key))`.
- Limpeza entre testes: `AbstractIntegrationTest.limparRedis()` (`flushDb()`).

## Convenções

- Nomes: `deve[Comportamento]_quando[Condição]()`. Sem comentários no corpo do teste.
- Cobertura mínima: **80%** nas classes novas/alteradas (70% é o piso bloqueante).
- Regressão obrigatória: rode os testes existentes do módulo afetado em toda tarefa.

## Contratos internos (sem Pact)

A compatibilidade do contrato Feign `auth-server ↔ user-service` é validada por
testes de integração com **WireMock** no authorization-server, não por Pact.
