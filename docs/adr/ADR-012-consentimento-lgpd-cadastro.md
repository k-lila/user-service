# ADR-012: Consentimento LGPD no cadastro (aceite versionado)

- **Status:** aceita
- **Data:** 2026-06-17
- **Serviço alvo:** user-service, login-interface
- **Tarefa relacionada:** RELATORIOA — dimensão LGPD, "Base legal / consentimento"

## Contexto

Como controlador de dados pessoais (LGPD), o cadastro precisa registrar a **base legal**: o aceite
dos termos de serviço e da política de privacidade, com timestamp. Não havia nenhum registro de
consentimento — nem no contrato (`UserRequestDTO`), nem no schema (`users`), nem no front.

Restrições do projeto: o registro usa grupos de Bean Validation (`OnCreate`/`Default`) para
distinguir cadastro de update; o front é BFF (registro é `POST /v1/users/register` público).

## Decisão

**Aceite versionado, obrigatório no cadastro.**

- **Contrato (`UserRequestDTO`):** novo campo `Boolean termsAccepted`, validado **só** no grupo
  `OnCreate`: `@NotNull` (cobre ausência) + `@AssertTrue` (rejeita `false`; `@AssertTrue` trata null
  como válido, daí o par). Ignorado no update (grupo `Default`).
- **Schema (`User`/coleção `users`):** novos campos `consentAcceptedAt` (Instant) e `termsVersion`
  (String). **Sem `@NotNull`** no entity — nullable para não quebrar o re-save de usuários legados
  anteriores ao campo; em novos cadastros são sempre setados.
- **Serviço (`RegisterService`):** no `registerUser`, seta `consentAcceptedAt = Instant.now()` e
  `termsVersion` a partir de `app.terms.version` (`${TERMS_VERSION:v1}`). **Bump da versão** quando a
  política mudar permite exigir re-consentimento futuro (a versão aceita fica provada por usuário).
- **Resposta (`UserResponseDTO`):** expõe `consentAcceptedAt`/`termsVersion` (nullable para legados).
- **Front (`RegisterBox`):** checkbox de aceite com links para `/terms` e `/privacy`; botão "Criar
  conta" **desabilitado** até marcar; `termsAccepted` incluído no payload (`RegisterRequest`).

## Consequências

- **Positivo:** base legal registrada e versionada já no nascimento do dado; o `OnCreate` reaproveita
  o mecanismo existente (update não é afetado). Compatível com dados legados (campos nullable).
- **Negativo / dívida:** as páginas `/terms` e `/privacy` ainda não existem como conteúdo (links
  apontam para rotas a criar); não há fluxo de **re-consentimento** ao mudar `termsVersion` (a âncora
  está pronta, o fluxo é trabalho futuro).
- **Contrato:** mudança **aditiva** em `UserRequestDTO` (campo novo obrigatório no cadastro) e em
  `UserResponseDTO`/schema. Clientes do registro precisam enviar `termsAccepted: true`.
- **Testes:** DTO/controller (400 sem consentimento ou com `false`, 201 com `true`), `RegisterService`
  (timestamp + versão setados), integração (persistência), e front (checkbox renderiza, bloqueia o
  submit, payload inclui `termsAccepted`).

## Alternativas consideradas

- **Só timestamp (sem versão):** mais simples, mas sem âncora de qual política foi aceita — inviabiliza
  re-consentimento ao mudar os termos. Rejeitado.
- **`@NotNull` nos campos do entity:** rejeitado — quebraria o re-save de usuários legados sem o campo.
- **Consentimento como entidade/coleção separada (histórico de consentimentos):** mais flexível para
  múltiplos consentimentos no tempo, mas excessivo para o aceite único de cadastro; evolução possível
  se surgirem múltiplas bases legais.
