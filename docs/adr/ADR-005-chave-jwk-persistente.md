# ADR-005: Chave JWK persistente (par RSA fixo com `kid` estável, carregado de PEM)

- **Status:** aceita
- **Data:** 2026-06-15
- **Serviço alvo:** authorization-server

> ADR retroativo: a decisão já está implementada e em produção no blueprint; este
> registro a formaliza para dar rastreabilidade.

> **Atualização (2026-06-17, gap 0.1 RELATORIOA fechado):** a chave de assinatura **não vive
> mais no repositório**. É gerada fora do versionamento por `infra/jwk/gen-keys.sh` (PKCS#8 +
> X.509); `authorization-server/src/main/resources/keys/` está no `.gitignore`; o CI gera um par
> efêmero por run; a chave dev antiga foi rotacionada e é tratada como comprometida/inerte. Na
> base secrets-native ([[ADR-009]]) o par é Docker secret (`jwk_private`/`jwk_public`, via
> `JWK_PRIVATE_KEY=file:/run/secrets/jwk_private`). A decisão de **par fixo com `kid` estável**
> abaixo permanece inalterada — só mudou a **origem** das chaves (fora do repo, não no classpath).

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
- ~~A **chave de dev vive no repositório** (`keys/app.{key,pub}`)~~ — **resolvido (2026-06-17,
  gap 0.1):** a chave não é mais versionada; é gerada fora do repo por `infra/jwk/gen-keys.sh` e
  o diretório `keys/` está no `.gitignore` (ver nota de atualização no topo e [[ADR-009]]). Em
  produção, montar via `JWK_*` (PEM como Docker secret).
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
