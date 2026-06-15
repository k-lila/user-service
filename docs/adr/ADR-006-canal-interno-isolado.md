# ADR-006: Canal interno isolado auth↔user protegido por shared secret `X-Internal-Token`

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** user-service / authorization-server

> ADR retroativo: a decisão já está implementada e em produção no blueprint; este
> registro a formaliza para dar rastreabilidade.

## Contexto

Durante o login, o authorization-server precisa dos dados de credencial e roles do usuário
(hash BCrypt, roles, permissions) para validar a senha e customizar o JWT. Duas restrições
do projeto moldam a solução:

- **Separação rígida de responsabilidades:** o auth-server **não acessa o MongoDB** — apenas
  o user-service fala com o banco de usuários.
- O endpoint que expõe esses dados **não pode ser público**: ele devolve material sensível
  (hash de senha, roles) e não tem nada a fazer na superfície externa da API.

É preciso, portanto, um canal **exclusivo** auth-server → user-service, fora do alcance do
gateway e protegido contra acesso direto.

## Decisão

Criar um **canal interno isolado** entre os dois serviços:

- O user-service expõe `InternalUserController` com `GET /internal/users/email/{email}`,
  consumido pelo auth-server via Feign (`IUserClient` — ver [[ADR-004]]).
- Esse path **não é roteado pelo gateway** (não há rota `/internal/**` em `GatewayRouter`) e
  **não aparece no Swagger** — é invisível à superfície externa.
- É protegido por um **shared secret** no header `X-Internal-Token`:
  - `InternalTokenFilter` (user-service, `OncePerRequestFilter`) valida o header em toda
    requisição a `/internal/**`; ausência/divergência → **403** (`ProblemDetail`,
    `SC_FORBIDDEN`).
  - `FeignConfig` (auth-server) registra um `RequestInterceptor` que injeta o
    `X-Internal-Token` em toda chamada Feign ao user-service, casando com o filtro.
- Acesso direto à porta 8090 sem o header → 403.

Sem mudança de contrato de API **externo** (o endpoint não faz parte do contrato público).

## Consequências

**Positivas:**
- Mantém a **separação rígida**: o auth-server obtém os dados sem tocar o MongoDB.
- O canal é **exclusivo e auditável**; a superfície externa não expõe dados sensíveis de
  credencial.
- Defesa em profundidade: mesmo quem alcance a porta 8090 na rede interna precisa do segredo.

**Negativas / atenção:**
- O `X-Internal-Token` é um **shared secret estático** — rotação é **manual** e exige
  coordenar os dois serviços (ponto operacional). Em prod, vir do config-server via env.
- É autenticação de **serviço por segredo compartilhado**, não mTLS — adequado ao blueprint,
  mas um consumidor com requisitos mais altos trocaria por mTLS (ver Alternativas).
- O filtro cobre `/internal/**` por path: novos endpoints internos herdam a proteção, mas é
  preciso mantê-los sob esse prefixo.

**Testes de regressão:** acesso sem o header → 403 e com o header → 200 cobertos em
`InternalUserControllerTest` (`@WebMvcTest`) e no fluxo de integração auth↔user.

## Alternativas consideradas

- **Auth-server acessando o MongoDB diretamente.** Descartada: viola a separação rígida de
  responsabilidades, princípio central do projeto.
- **Expor o endpoint publicamente com autorização por role/scope.** Descartada: amplia a
  superfície de ataque e exporia material de credencial num path roteável; o canal interno
  isolado é mais defensável.
- **mTLS entre auth-server e user-service.** Mais robusto (identidade mútua por certificado),
  porém com custo de PKI/rotação de certs — fora do escopo da v1; fica como evolução do
  consumidor. O shared secret é o curativo pragmático.
