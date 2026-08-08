# ADR-023: Smoke-test automatizado da topologia de login sob hostname único

- **Status:** aceita
- **Data:** 2026-08-06
- **Serviço alvo:** gateway (rotas + `SecurityConfig`); login-interface (nginx); authorization-server (front-channel); CI (`.github/workflows/ci.yml`)
- **Tarefa relacionada:** gap de asseguramento identificado pelo `security-reviewer` na revisão do [ADR-019](ADR-019-correcao-elos-login-hostname-unico.md), registrado em `.claude/memory/decisions.md` como "pendente de decisão do humano"

## Contexto

O [ADR-019](ADR-019-correcao-elos-login-hostname-unico.md) documentou quatro elos quebrados na cadeia
`nginx (interface) → gateway → authorization-server` sob hostname único — **todos confirmados ao vivo em
produção**, nenhum pego antes do deploy:

- **Elo 2** — `trusted-proxies` sem default no `spring-cloud-gateway-server-webflux` 5.0.0 (regressão
  silenciosa de upgrade): sem a propriedade, `XForwardedHeadersFilter` não era registrado, e o
  auth-server recebia requests sem `X-Forwarded-Host`/`Proto`, construindo `Location` com o hostname
  interno do container.
- **Elo 3** — `/login` sem `location` no `nginx.conf`: a request caía no `try_files` do SPA, que
  renderizava `<Login/>` de novo, criando um loop. O mesmo problema atingia `/default-ui.css` (CSS do
  formulário do IdP, servido como `text/html` por engano).
- **Elo 6** — o `ForwardedHeaderTransformer` do gateway rodava *antes* dos `GatewayFilter` do SCG e
  reescrevia `remoteAddress` a partir do `X-Forwarded-For` que o nginx repassava, neutralizando
  silenciosamente o fix do Elo 2 — só visível comparando o `Location` retornado com e sem XFF na
  request.
- **BUG-001** — `POST /login` retornando 403: o `_csrf` que o próprio auth-server embute no formulário
  não bate com o `XSRF-TOKEN` do gateway, e o `ServerCsrfTokenRequestAttributeHandler` barra a request
  antes de ela alcançar o auth-server.

Nenhuma camada de teste hoje exercita essa cadeia real. Testes unitários e `@WebMvcTest` batem direto no
controller de cada serviço, sem proxy no meio. Os testes de integração com Testcontainers sobem
Mongo/Redis/Postgres/WireMock, mas nunca o container `interface` (nginx). O job `compose-validate` no CI
roda só `docker compose -f docker-compose.yml config -q` — validação de sintaxe YAML, não de
comportamento em runtime. E o ambiente de dev mascara estruturalmente esses bugs: o browser vai direto a
`localhost:8082`, sem passar pelo gateway, então o CSRF do gateway e o roteamento do nginx nunca entram
em jogo. Resultado: a topologia que efetivamente vai ao ar só era exercitada por um humano clicando no
app depois do deploy.

Na revisão do ADR-019, o `security-reviewer` propôs como mitigação inicial um **checklist manual de
smoke-test** — e a revisão seguinte rejeitou a própria proposta, registrando em
`.claude/memory/decisions.md` que ela "repete o antipadrão que causou o Elo 1": depender de o operador
lembrar de rodar algo manualmente, exatamente o problema que motivou criar o serviço `assert-env` no
`docker-compose.deploy.yml` como **invariante forçada** (falha automática antes de qualquer serviço
atender tráfego) em vez de instrução em comentário. O controle proporcional identificado — e nunca
implementado — é um smoke-test **automatizado** contra a topologia real, cobrindo cinco asserções HTTP
pontuais.

## Decisão

Construir um smoke-test automatizado que sobe a topologia real (nginx + gateway + authorization-server)
e verifica, via HTTP simples, os pontos exatos onde os elos do ADR-019 quebraram. Nenhum dos bugs
históricos é de comportamento JS renderizado — são todos de status code, header `Location` ou
`Content-Type` — então o mecanismo é **script bash + curl**, não um browser headless.

### Mecanismo

1. **Script:** `infra/smoke-test/login-topology-smoke-test.sh`, seguindo a convenção de scripts de
   infraestrutura já existente (`infra/secrets/gen-secrets.sh`, `infra/jwk/gen-keys.sh`).

2. **Topologia subida:** `docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d`,
   **excluindo** o serviço `cloudflared` (não roda em CI — sem DNS/túnel real). `PUBLIC_ORIGIN` e
   `PUBLIC_HOST` recebem um valor fictício mas coerente entre si (ex.: `PUBLIC_HOST=smoke-test.local`,
   `PUBLIC_ORIGIN=http://smoke-test.local`), satisfazendo a asserção do `assert-env` sem exigir domínio
   real.

3. **Acesso à rede Docker interna:** os comandos curl rodam de dentro de um container efêmero
   (`curlimages/curl`) conectado à mesma rede do compose via `docker run --rm --network <rede-do-compose>
   ...` — o mesmo padrão de "container minimalista de infraestrutura" que o `assert-env` já usa
   (`alpine:3`), em vez de expor portas novas no host.

4. **As cinco asserções**, mapeadas diretamente aos elos do ADR-019:

   | # | Verificação | Comando (esquema) | Elo/bug coberto |
   |---|---|---|---|
   | a | `GET /login` retorna o form do IdP, não `index.html` do SPA | `curl -s http://interface/login \| grep <marcador do form do auth-server>` | Elo 3 |
   | b | `GET /default-ui.css` retorna `Content-Type: text/css` | `curl -sI http://interface/default-ui.css \| grep 'text/css'` | Elo 3 |
   | c | `POST /login` não retorna 403 | `curl -s -o /dev/null -w '%{http_code}' -X POST http://interface/login ...` ≠ `403` | BUG-001 |
   | d | `Location` de `GET /oauth2/authorize` (sem sessão) carrega host/proto públicos, não o hostname interno do container | `curl -sI http://interface/oauth2/authorize \| grep 'Location: https://smoke-test.local'` | Elo 2 + Elo 6 |
   | e | A base do compose não publica `8081`/`5173` | `docker compose -f docker-compose.yml config` — checagem estática, sem stack no ar | G10 (ADR-019) |

   A asserção (e) não depende da stack em execução — roda antes das demais, como guarda rápida.

5. **Integração no CI:** novo job `smoke-test-login` em `.github/workflows/ci.yml`, paralelo aos jobs
   existentes (`backend`, `frontend`, `compose-validate`), sem dependência entre eles. Roda em todo
   push/PR — o CI hoje não tem mecanismo de path-filtering em nenhum job, e o custo de introduzir uma
   action de terceiros só para isso não se paga dado que o job é barato (subir a stack + 5 curls).
   `docker compose down -v` sempre ao final (`if: always()`), no mesmo padrão dos demais jobs que fazem
   upload de artefato com `if: always()`.

## Consequências

**Positivas**

- Fecha a classe de bug do ADR-019 que hoje só é detectada manualmente, depois do deploy: Elos 2, 3, 6 e
  BUG-001 passam a ter barreira automática pré-merge. O Elo 1 (`PUBLIC_ORIGIN`/`PUBLIC_HOST`) já está
  coberto pelo `assert-env` e não precisa de asserção redundante aqui.
- É o primeiro teste do repositório a exercitar o nginx real — fecha o gap estrutural em que dev local e
  Testcontainers mascaram bugs de roteamento de borda.
- Reaproveita padrões já estabelecidos no projeto (container efêmero minimalista, `docker-compose.deploy.yml`
  como base), sem introduzir uma ferramenta de E2E nova ao stack.

**Negativas / a observar**

- CI mais lento: o job sobe a stack completa (nginx + gateway + authorization-server + dependências),
  ao contrário dos jobs `backend`/`frontend` que rodam isolados.
- Risco de flakiness por timing de healthcheck — mitigar com espera ativa (`docker compose ... wait` ou
  polling) em vez de `sleep` fixo.
- Manutenção do script quando rotas do nginx/gateway mudarem — mesmo risco de qualquer teste de
  integração, mas concentrado num script bash em vez de JUnit, exigindo atenção explícita de quem editar
  `nginx.conf`/`GatewayRouter` no futuro.
- **Fora de escopo, de propósito:** riscos residuais como R-09 (rede Docker flat — um container hostil
  na mesma rede pode forjar `X-Forwarded-*`) e os CSRF latentes BUG-002/BUG-004/BUG-005 (paths hoje
  inertes, como `/oauth2/authorize` de consentimento e `/connect/logout` sem `id_token_hint`) não são
  cobertos — são riscos de outra natureza, não bugs de roteamento pegáveis por asserção HTTP simples
  contra a topologia normal.

## Alternativas consideradas

- **Playwright/Cypress E2E completo:** descartada. Os bugs históricos são de nível HTTP/header
  (status code, `Location`, `Content-Type`), não de comportamento JS renderizado — um browser headless
  seria mais caro de manter e mais frágil sem cobrir nada que curl não cubra.
- **Checklist manual de smoke-test:** descartada — já rejeitada em `.claude/memory/decisions.md` por
  repetir o antipadrão que causou o Elo 1 (depender da memória do operador).
- **Validar só em staging pós-deploy, não em CI pré-merge:** descartada. O próprio ADR-019 mostra que os
  quatro elos só apareceram em produção justamente por não existir barreira pré-deploy — o objetivo
  deste smoke-test é pegar o defeito antes do merge, não depois do deploy.
- **Path-filtering do job via action de terceiros** (rodar só quando `nginx.conf`/`GatewayRouter`/compose
  mudarem): descartada por ora — o job é barato o bastante para rodar sempre, evitando a dependência
  extra e o risco de o filtro deixar passar uma mudança indireta (ex.: em `SecurityConfig`) que também
  afete a cadeia. Revisitar se o tempo total de CI crescer de forma perceptível.
