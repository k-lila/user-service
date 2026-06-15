# ADR-005: Chave JWK persistente (par RSA fixo com `kid` estável, carregado de PEM)

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** authorization-server

> ADR retroativo: a decisão já está implementada e em produção no blueprint; este
> registro a formaliza para dar rastreabilidade.

## Contexto

O authorization-server assina os JWTs (access tokens / OIDC id tokens) com uma chave RSA e
publica a chave pública no JWKS, identificada por um `kid`. Os resource servers (gateway,
user-service) validam os tokens buscando a chave pelo `kid` no JWKS.

O caminho "padrão" de exemplos do Spring Authorization Server **gera um par RSA novo a cada
boot** (`KeyGenerator`). Isso quebra o blueprint de duas formas:

1. **N instâncias divergem.** Cada instância do auth-server geraria um `kid` diferente — um
   token assinado pela instância A não validaria contra o JWKS da instância B. Incompatível
   com a escala horizontal pretendida ([[ADR-003]]).
2. **Restart invalida tokens.** Reiniciar o auth-server troca a chave, invalidando todos os
   tokens em circulação.

## Decisão

Usar um **par RSA fixo com `kid` estável**, carregado de arquivos PEM, em
`authorizationserver/config/JWKConfig.java`:

- `kid` estável (`user-service-key`, via `${jwk.key-id}`); chave privada/pública lidas de
  `Resource` PEM (`${jwk.private-key}` / `${jwk.public-key}`) e convertidas com
  `RsaKeyConverters.pkcs8()` / `.x509()`.
- O `JWKSource<SecurityContext>` é um `ImmutableJWKSet` montado a partir desse par — não há
  geração por boot.
- **Defaults de classpath** (`src/main/resources/keys/app.{key,pub}`) são chaves **DE
  DESENVOLVIMENTO** — um **gap de segurança conhecido e aceito**, análogo ao keyfile de dev
  do MongoDB. Em produção, sobrescrever via `JWK_*` (PEM montado como secret).

Sem mudança de contrato de API (o formato do JWT e do JWKS é o padrão OIDC).

## Consequências

**Positivas:**
- O **JWT é verificável de forma estável** entre restarts e entre instâncias — pré-requisito
  da escala horizontal do auth-server.
- A **rotação de chave** passa a ser uma operação **deliberada** (trocar o PEM + `kid`), não
  um efeito colateral de restart.
- O `kid` estável permite cache de JWKS eficiente nos resource servers.

**Negativas / atenção:**
- A **chave de dev vive no repositório** (`keys/app.{key,pub}`) — gap aceito, nomeado em
  `CLAUDE.md` §"Gaps de Segurança Conhecidos". **Nunca** usar em produção; o blueprint
  apenas garante que seja parametrizável (`JWK_*`), não resolvido.
- Sem rotação automática: trocar a chave exige operação manual e convivência temporária com
  o `kid` antigo no JWKS se houver tokens em voo (não implementado — rotação é exercício do
  consumidor).

## Alternativas consideradas

- **Gerar o par a cada boot (default de exemplos do SAS).** Descartada: quebra a validação
  com N instâncias e invalida tokens a cada restart — as forças motrizes desta decisão.
- **KMS externo / HSM para a chave.** Fora de escopo da v1: mais robusto (chave nunca em
  disco), mas depende de infra real do consumidor — tratado como exercício do consumidor,
  junto dos demais gaps de prod (cert ACME, etc.).
- **Chave simétrica (HMAC).** Descartada: exigiria compartilhar o segredo de assinatura com
  todos os resource servers; RSA assimétrico permite distribuir só a pública via JWKS.
