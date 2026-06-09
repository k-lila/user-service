# Checklist — Blueprint de Sistema de Usuários em Microsserviços

> Referência genérica e independente de projeto. Cada item é uma feature observável — verificável sem ambiguidade. Todos os itens são **DEVE TER**: a ausência de qualquer um compromete o caráter de blueprint pronto para produção.

**Legenda:** `[x]` implementado · `[~]` parcial (gap conhecido) · `[ ]` ausente

## Índice

- [1. Identidade e Autenticação](#1-identidade-e-autenticação)
- [2. Autorização](#2-autorização)
- [3. Ciclo de Vida do Usuário](#3-ciclo-de-vida-do-usuário)
- [4. Segurança Operacional](#4-segurança-operacional)
- [5. Escalabilidade Horizontal](#5-escalabilidade-horizontal)
- [6. Alta Disponibilidade de Infraestrutura](#6-alta-disponibilidade-de-infraestrutura)
- [7. Resiliência de Aplicação](#7-resiliência-de-aplicação)
- [8. Observabilidade](#8-observabilidade)
- [9. Qualidade de API](#9-qualidade-de-api)
- [10. Testes e Entrega](#10-testes-e-entrega)
- [Tabela de Números-Chave](#tabela-de-números-chave)

---

## 1. Identidade e Autenticação

- [x] Registro com validação declarativa: nome, e-mail único, política mínima de senha
- [x] Login por credenciais (username/password) com hash irreversível (BCrypt/Argon2)
- [x] Fluxo OAuth2/OIDC completo: authorization code + PKCE + refresh token
- [x] Logout com encerramento de sessão no IdP (RP-Initiated Logout)
- [x] Sessão distribuída — sem estado local de processo (Redis ou equivalente)
- [x] Tokens JWT stateless com `kid` estável — multi-instância sem regenerar chave por boot

## 2. Autorização

- [x] RBAC com pelo menos dois níveis: usuário comum e administrador
- [x] Autorização por endpoint — não apenas autenticação (`@PreAuthorize` ou equivalente)
- [~] Claims de autorização no token: roles + permissions derivadas de roles, nunca hardcoded _(roles ✓; `permissions` hardcoded `["users.read","users.write"]` para todos os usuários — G8/C8)_
- [x] Endpoint de administração separado com controle de acesso próprio
- [x] Canal interno entre serviços isolado da borda pública e autenticado (shared secret ou mTLS)

## 3. Ciclo de Vida do Usuário

- [x] CRUD completo: criar, ler, atualizar, desativar
- [x] Soft delete — conta inativada, dado preservado
- [x] Hard delete — remoção permanente (conformidade LGPD/GDPR)
- [x] Auto-gestão — usuário pode atualizar e remover seus próprios dados
- [x] Respostas de API sem campos sensíveis (sem `passwordHash`, sem `roles` expostas ao cliente)

## 4. Segurança Operacional

- [ ] TLS/HTTPS em todas as comunicações externas _(G1 — aberto)_
- [ ] Segredos fora do repositório: variáveis de ambiente ou secret manager, nunca hardcoded _(G4/C11 — credenciais em claro no `docker-compose.yml`)_
- [x] Rate limiting por tier: por IP em endpoints públicos e por usuário em endpoints autenticados
- [x] Proteção CSRF nos endpoints de mutação acessados pelo browser
- [~] Política de CORS definida na borda e configurável por ambiente, não hardcoded _(G7/C12 — CORS duplicado em 3 módulos com origens fixas; curativo aplicado, solução completa pendente)_
- [ ] Proteção anti-brute-force com lockout/backoff por conta no login _(G10/C19 — aberto; só rate limit por IP no gateway)_
- [x] Comparação de tokens e secrets em tempo constante (sem vulnerabilidade de timing)
- [x] Mascaramento de PII em logs — e-mails e dados pessoais nunca em claro
- [ ] Endpoints de métricas e actuator restritos na borda pública _(G6/C18 — `/actuator/**` é `permitAll` no gateway)_

## 5. Escalabilidade Horizontal

- [x] Todos os serviços de aplicação são stateless — sessão e cache em infraestrutura externa
- [x] Estado OAuth (authorizations, consents, clients) em banco compartilhado, não em memória
- [x] Cache distribuído com TTL definido e estratégia de evicção consistente
- [x] Service discovery dinâmico (Eureka, Consul ou equivalente)
- [x] Load balancing entre instâncias sem configuração estática de IPs

## 6. Alta Disponibilidade de Infraestrutura

- [x] Banco de dados primário em modo replicado (replica set / cluster) — sem SPOF
- [x] Cache em modo sentinel/cluster — sem SPOF
- [x] Service discovery em peer replication — sem SPOF
- [x] Configuração centralizada em modo replicado com load balancer — sem SPOF

## 7. Resiliência de Aplicação

- [x] Circuit breaker nas chamadas síncronas entre serviços, com fallback definido
- [x] Graceful shutdown — drena requisições em andamento antes de encerrar
- [x] Health probes de aplicação: readiness e liveness via actuator ou equivalente
- [x] Healthchecks de container com ordem de subida declarada (`depends_on: service_healthy`)

## 8. Observabilidade

- [x] Logs estruturados com `traceId`/`spanId` propagados entre serviços (B3 ou equivalente)
- [x] Rastreamento distribuído (Zipkin, Jaeger ou equivalente)
- [x] Métricas expostas em formato padronizado (Prometheus ou equivalente)
- [x] Dashboards operacionais pré-configurados (Grafana ou equivalente)
- [x] SLOs de latência definidos e mensuráveis (buckets de histograma)
- [x] Correlação de requisições na borda: correlation ID injetado e propagado downstream

## 9. Qualidade de API

- [ ] Contrato de erro uniforme em todos os endpoints (RFC 7807 / `ProblemDetail` ou equivalente) _(C9 — `GlobalExceptionHandler` devolve `String` crua; apenas `@Valid` 400 foi corrigido)_
- [x] Documentação de API gerada automaticamente e acessível (OpenAPI/Swagger)
- [ ] API versionada (`/v1/...`) antes de adicionar novos domínios sobre a base
- [~] Validação de entrada declarativa (Bean Validation ou equivalente) — nunca somente manual _(`@Valid` usado na maioria dos campos; senha com `@Size` nullable sem `@NotBlank` + checagem manual no `RegisterService` — G9/C13)_

## 10. Testes e Entrega

- [x] Testes unitários para lógica de negócio sem dependências externas
- [x] Testes de controller/API validando status HTTP, autorização e contratos de resposta
- [x] Testes de integração com infraestrutura real (Testcontainers ou equivalente) — sem mock de banco
- [ ] Pipeline de CI hermético: zero dependências externas nos testes; BUILD SUCCESS obrigatório antes de merge _(sem CI/CD; `contextLoads` do gateway faz OIDC discovery real e falha sem o stack ativo)_

---

## Tabela de Números-Chave

### Desempenho

| Métrica | Mínimo aceitável | Referência boa | Como medir |
|---------|-----------------|----------------|------------|
| Latência p50 — leitura (cache hit) | < 20 ms | < 5 ms | Histograma Prometheus / Grafana |
| Latência p99 — leitura (cache hit) | < 100 ms | < 50 ms | Idem |
| Latência p99 — leitura (cache miss) | < 500 ms | < 200 ms | Idem |
| Latência p99 — registro | < 2 s | < 1 s | Dominado pelo custo do hash |
| Latência p99 — login completo (OAuth2) | < 3 s | < 1,5 s | Hash + Feign + Redis |
| Cache hit ratio (estado estável) | > 80% | > 95% | `cache_gets{result="hit"}` / total |
| Throughput de login por instância | ≥ 3 req/s | ≥ 10 req/s | Gargalo no hash; escala com instâncias |

### Segurança

| Parâmetro | Valor mínimo | Justificativa |
|-----------|-------------|---------------|
| Custo BCrypt | 10 | OWASP mínimo; ≥ 100 ms por operação |
| Argon2id (alternativa ao BCrypt) | m=19456, t=2, p=1 | OWASP mínimo |
| TTL do access token (JWT) | ≤ 1 hora | Limita janela de exploração de token comprometido |
| TTL do refresh token | ≤ 30 dias com rotação | Renovação obrigatória; revogar no logout |
| Tamanho mínimo de senha | ≥ 8 caracteres + complexidade | NIST SP 800-63B |
| Lockout após N falhas de login | ≤ 10 tentativas | Equilíbrio entre usabilidade e segurança |
| Duração de lockout / backoff | ≥ 5 min ou exponencial | Tempo suficiente para alertar e dificultar automação |
| Rate limit — endpoint de registro | ≤ 5 req/s por IP | Protege contra spam de cadastro |
| Rate limit — endpoint de login | ≤ 10 req/s por IP | Complementa o lockout por conta |
| Comparação de secrets | tempo constante O(n) | `MessageDigest.isEqual`, `hmac_compare` ou equivalente |

### Qualidade

| Métrica | Mínimo | Referência boa |
|---------|--------|----------------|
| Cobertura de linhas — serviço core | ≥ 80% | ≥ 90% |
| Cobertura de branches — serviço core | ≥ 70% | ≥ 85% |
| Cobertura de controller — endpoints críticos | 100% dos status HTTP possíveis | 100% incluindo cenários de autorização |
| Cobertura de integração | fluxos principais: registro, busca, update, delete | + índice único, evicção de cache, inativação |
| Testes dependentes de serviço externo em CI | 0 | 0 (inegociável) |
| Módulos com BUILD SUCCESS em CI | 100% | 100% (inegociável) |

### Resiliência

| Parâmetro | Valor mínimo | Referência boa |
|-----------|-------------|----------------|
| RTO após falha de nó de banco (replica set) | ≤ 30 s | ≤ 15 s |
| RTO após falha de nó de cache (sentinel) | ≤ 30 s | ≤ 15 s |
| Timeout máximo por chamada entre serviços | ≤ 5 s | ≤ 3 s |
| Janela deslizante do circuit breaker | ≤ 20 chamadas | 10 chamadas |
| Threshold de falha para abrir o circuito | ≥ 50% | 50% |
| Tempo de circuito aberto (antes do half-open) | ≥ 5 s | 10 s |
| Chamadas permitidas no half-open | ≥ 1 | 2 |
| Graceful shutdown — tempo máximo de drenagem | ≤ 30 s | ≤ 10 s |
| Quorum do sentinel / cluster de cache | maioria simples (2 de 3) | maioria simples |
