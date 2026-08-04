# ADR-019: Correção dos quatro elos de login sob hostname único (trusted-proxies, /login ao IdP, G10, PUBLIC_ORIGIN)

- **Status:** aceita
- **Data:** 2026-08-03
- **Serviço alvo:** gateway (config + rotas + SecurityConfig); login-interface (nginx + SPA router); docker-compose (G10); config-server (gateway.yml)
- **Tarefa relacionada:** FASE 3 do workflow `feature.md` — correção de defeitos confirmados ao vivo em `https://app.k-lila.com.br`; estende e fecha gaps abertos pelo [ADR-018](ADR-018-rota-logout-front-channel-borda.md)

## Contexto

Com a stack no ar via Cloudflare Tunnel, três defeitos sequenciais impediam o login. Um quarto defeito estava latente. Todos foram confirmados ao vivo (não são hipóteses):

**Elo 1 — `PUBLIC_ORIGIN` aponta para NXDOMAIN.** `.env` tinha `PUBLIC_ORIGIN=https://api.k-lila.com.br`; o CNAME existente é `app.k-lila.com.br`. O gateway emitiria 302 para um domínio que não resolve. Causa-raiz: `PUBLIC_HOST=app.k-lila.com.br` (correto) existia no `.env` mas não era consumido por nenhum arquivo de compose (só em comentário), tornando a divergência invisível.

**Elo 2 — gateway sem `trusted-proxies` (regressão SCG 5.0.0).** No `spring-cloud-gateway-server-webflux` **5.0.0** (Spring Cloud 2025.1.0), a propriedade `spring.cloud.gateway.server.webflux.trusted-proxies` não tem default. Sem ela, `XForwardedHeadersFilter` **e** `ForwardedHeadersFilter` não são registrados — os dois filtros são condicionados à mesma propriedade. O auth-server recebia as requests sem `X-Forwarded-Host`/`X-Forwarded-Proto` e construía `Location: http://d3ac6e89c3d9:8082/login`. Regressão silenciosa de upgrade: em versões anteriores do SCG os filtros tinham default ativo.

**Elo 3 — colisão `/login` no nginx.** Com Elos 1+2 corrigidos, o browser alcança o formulário do IdP em `/login` — mas `nginx.conf` não tinha `location /login`. A request caía em `location /` → `try_files` → `index.html` → SPA renderizava `<Login/>` → loop. O CSS do IdP (`/default-ui.css`) tinha o mesmo problema: sem rota no gateway e sem `location` no nginx, caía no `try_files` e era servido como `text/html` com `nosniff`.

**Elo 4 latente — round-trip de sessão sob HTTPS real.** Com os três elos acima corrigidos, a sessão PKCE/OAuth2 precisa sobreviver ao salto borda → gateway → auth-server com cookies `Secure`+`SameSite=Lax` — verificado pelo AC-24 após os demais ACs.

**Elo 6 — interação `ForwardedHeaderTransformer` × `trusted-proxies` × XFF do nginx.** Após o rebuild da stack (Elos 1–4 aplicados), o Elo 2 permaneceu aberto. Evidência:

```
sem X-Forwarded-For  → Location: https://app.k-lila.com.br/login   ✔
com X-Forwarded-For  → Location: http://9eff175e9084:8082/login     ✘
```

Causa raiz: `nginx.conf` enviava `X-Forwarded-For: $proxy_add_x_forwarded_for` nas 9 locations que fazem proxy para `gateway:8081`. O `ForwardedHeaderTransformer` (registrado por `strategy=framework`) é um `WebFilter` com prioridade máxima — roda **antes** dos `GatewayFilter` do SCG. Ele consumia o XFF e **reescrevia `remoteAddress` para o IP público do cliente**. Quando `XForwardedHeadersFilter` rodava em seguida e chamava `TrustedProxies.isTrusted(remoteAddress)`, recebia o IP público, que não casa o regex RFC1918 — e ficava silencioso (não emitia `X-Forwarded-Host`/`Proto` ao auth-server). O Elo 2 estava correto; o Elo 6 neutralizava-o em produção.

**G10 — portas publicadas na base do compose (ALTO).** `docker-compose.yml` publicava `8081:8081` (gateway) e `${WEB_HOST_PORT:-5173}:80` (interface) na **base**, não no override de dev. Com o deploy público no ar, qualquer máquina da LAN podia enviar `CF-Connecting-IP: <forjado>` diretamente ao gateway, neutralizando o particionamento por IP do rate limit e o lockout anti-brute-force — falsificando a premissa do ADR-010. G10 e `trusted-proxies` são **controles interdependentes**: o regex RFC1918 amplo de `trusted-proxies` seria inseguro com as portas abertas na base (host/LAN podia forjar `X-Forwarded-*`).

## Decisão

### Tabela de propriedade de paths na borda (após esta ADR)

| Path(s) | Dono semântico | Tratamento no nginx | Rota no gateway |
|---------|---------------|---------------------|-----------------|
| `/login`, `/login?error`, `/login/oauth2/**` | IdP (authorization-server) | `location /login` → `proxy_pass gateway` | `auth-login` (rate limit MED/IP) |
| `/default-ui.css` | IdP (CSS do formulário) | `location = /default-ui.css` → `proxy_pass gateway` | `auth-default-ui` (rate limit LOW/IP, `permitAll`) |
| `/oauth2/**` | authorization-server | `location /oauth2` → `proxy_pass gateway` | `oauth` (rate limit MED/IP) |
| `/connect/**` | authorization-server (RP-Initiated Logout) | `location /connect/` → `proxy_pass gateway` | `connect-logout` (ADR-018, rate limit MED/IP) |
| `/` | SPA (`<Login/>` diretamente) | `location /` → `try_files` | — (SPA) |
| `/register`, `/dashboard` | SPA | `location /` → `try_files` | — (SPA) |
| `/v1/users/**`, `/v1/admin/**` | user-service | `location /v1/users`, `/v1/admin` | `user-service`, `admin-service` |
| `/swagger-ui`, `/v3/api-docs` | gateway (agregado) | `location /swagger-ui`, `/v3/api-docs` | `user-service-docs`, `authorization-server-docs` |

**Antes desta ADR**, `location /login/oauth2` era o único bloco com `/login`. Agora um único bloco `location /login` (prefixo, que subsume `/login/oauth2/**` por abrangência) substitui os dois — o `location /login/oauth2` removido estava semanticamente inconsistente com a nova propriedade do path.

### Elo 1 — Corrigir `PUBLIC_ORIGIN` + asserção de coerência `PUBLIC_ORIGIN` ↔ `PUBLIC_HOST`

`.env`: `PUBLIC_ORIGIN=https://app.k-lila.com.br`.

**Asserção de coerência via serviço init-container `assert-env` em `docker-compose.deploy.yml`.** O racional: um passo manual de instrução (`.env.example` pedindo que os dois valores fiquem em sincronia) foi justamente o que falhou — quem não leu o comentário criou a divergência. A asserção existe para converter essa instrução em **invariante forçada**, falhando a stack antes de qualquer serviço atender tráfego, sem depender de memória do operador. O mecanismo:

```yaml
assert-env:
  image: alpine:3
  environment:
    PUBLIC_ORIGIN: ${PUBLIC_ORIGIN:?defina PUBLIC_ORIGIN no .env}
    PUBLIC_HOST: ${PUBLIC_HOST:?defina PUBLIC_HOST no .env}
  command: >-
    sh -c 'expected=$(echo "$$PUBLIC_ORIGIN" | sed "s|^https\?://||" | sed "s|/.*||");
    if [ "$$expected" != "$$PUBLIC_HOST" ]; then
      echo "[ERRO] PUBLIC_ORIGIN=$$PUBLIC_ORIGIN implica hostname=$$expected, mas PUBLIC_HOST=$$PUBLIC_HOST. Mantenha os dois em sincronia no .env antes de subir a stack.";
      exit 1;
    fi;
    echo "[OK] PUBLIC_ORIGIN e PUBLIC_HOST coerentes ($$PUBLIC_HOST)."'
```

O gateway ganha `depends_on: assert-env: condition: service_completed_successfully`. Se os valores divergem, o container sai com código 1 e o `docker compose up` falha antes do gateway alcançar o healthcheck — com mensagem clara nomeando ambas as variáveis.

Alternativas descartadas: (a) validação em Java no gateway (falha depois de servir tráfego, não antes); (b) script shell de pré-subida (depende de o operador lembrar de rodar — exatamente o problema original). O init-container é o único que falha *antes* e é *automático*.

Não quebra o `compose-validate` do CI: o CI roda `docker compose -f docker-compose.yml config` (base apenas); `assert-env` existe só em `docker-compose.deploy.yml`.

### Elo 2 — `trusted-proxies` (regressão SCG 5.0.0)

Propriedades verificadas no jar `spring-cloud-gateway-server-webflux-5.0.0.jar` (`spring-configuration-metadata.json`):

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          trusted-proxies: ${GATEWAY_TRUSTED_PROXIES:10\..*|172\.(1[6-9]|2[0-9]|3[01])\..*|192\.168\..*}
          x-forwarded:
            for-enabled: false
            for-append: false
            host-enabled: true
            host-append: false
            port-enabled: false
            port-append: false
            proto-enabled: true
            proto-append: false
            prefix-enabled: false
            prefix-append: false
```

**Valor RFC1918 amplo:** a subnet `user-service-net` não é fixa (pool default do Docker — pode mudar em outra máquina). RFC1918 amplo é machine-agnostic e seguro porque, com G10 fechado, o único peer capaz de alcançar `gateway:8081` é um container da rede Docker.

**Knobs mínimos (`host` + `proto`):** `X-Forwarded-For` não é necessário para o redirect do IdP (o auth-server reconstrói a URL a partir de `X-Forwarded-Host` + `X-Forwarded-Proto`) e sua proliferação criaria ambiguidade com `CF-Connecting-IP`. `port-enabled: false` e `prefix-enabled: false` são desabilitados por não terem uso downstream.

**`append: false` em `host` e `proto`:** o nginx já envia `X-Forwarded-Proto: https`. Com `proto-append: true` (default), o filtro acrescentaria ao cabeçalho existente (`https, http` — duplo). Com `proto-append: false`, sobrescreve com o valor derivado do cabeçalho recebido (que é `https` vindo do nginx). `host-append: false` garante que o gateway seta `X-Forwarded-Host` a partir do `Host` que o nginx enviou (o hostname público), não acumulando.

**Efeito colateral — `ForwardedHeadersFilter` (RFC 7239):** a propriedade `trusted-proxies` também registra o `ForwardedHeadersFilter`, que emite o header `Forwarded` (RFC 7239) em todas as requests downstream. Impacto prático baixo — nenhum serviço downstream lê `Forwarded` hoje (todos usam `CF-Connecting-IP`). Documentado como efeito colateral conhecido.

**Interação com ADR-010:** `ClientIpResolver` nos dois serviços lê `CF-Connecting-IP` explicitamente. O nginx não filtra `CF-Connecting-IP` (confirmado em `nginx.conf:38-40`). O `X-Forwarded-For` desabilitado garante que não há ambiguidade de IP downstream.

### G10 — Fechar portas da base do compose

Os dois blocos `ports:` são movidos do `docker-compose.yml` para o `docker-compose.override.yml`:

- `gateway`: `ports: ["8081:8081"]` → override
- `interface`: `ports: ["${WEB_HOST_PORT:-5173}:80"]` → override

No modo deploy prod-safe (`docker compose -f docker-compose.yml up`), nenhuma das duas portas é publicada no host. Em dev (`docker compose up`), o override as republica automaticamente (comportamento inalterado para o desenvolvedor). Fecha a premissa do ADR-010.

### Elo 3 — Devolver `/login` ao IdP

**nginx.conf:** `location /login/oauth2 { ... }` substituído por `location /login { ... }` (prefixo que subsume `/login`, `/login?error` e `/login/oauth2/**`). `location = /default-ui.css { proxy_pass $gateway; }` adicionado **sem** `add_header` próprio — herda os headers de segurança do nível `server` (regra da armadilha do nginx: `add_header` local descartaria os do nível pai).

**GatewayRouter:** rota `auth-login` ganha `redisRateLimiterMed` + `ipKeyResolver` (o caminho de login é unauthenticated e sem sessão, logo `userKeyResolver` devolveria `"anonymous"` — raciocínio idêntico ao do `connect-logout` no ADR-018). Nova rota `auth-default-ui` (`/default-ui.css`, LOW/IP, `permitAll`).

**SecurityConfig:** `/default-ui.css` entra no `permitAll()`.

**SPA router:** `<Route path="/" element={<Navigate to="/login" />}>` e `<Route path="/login">` substituídos por `<Route path="/" element={<Login />} />`. `ProtectedLayout` redireciona para `/` (não `/login`). `RegisterBox.navigate('/login')` → `navigate('/')`. `useRegister.onSuccess` → `navigate('/')`.

### Elo 6 — Suprimir `X-Forwarded-For` no nginx antes do gateway

**Raiz do problema:** `server.forward-headers-strategy: framework` (gateway.yml) registra `ForwardedHeaderTransformer` como `WebFilter` de alta prioridade. Antes de qualquer `GatewayFilter` rodar, o transformer consome `X-Forwarded-For` e reescreve `remoteAddress` para o IP do cliente (público). O `XForwardedHeadersFilter` (SCG), ao chamar `TrustedProxies.isTrusted(remoteAddress)`, recebe o IP público — não casa RFC1918 — e fica silencioso. O Elo 2 (`trusted-proxies`) estava correto; o Elo 6 neutralizava-o porque o nginx enviava XFF ao gateway em todas as 9 locations.

**Alternativas descartadas:**

- **Option A — `server.forward-headers-strategy: none`:** fatal. Sem `ForwardedHeaderTransformer`, `request.getURI()` não tem o esquema reescrito de `X-Forwarded-Proto`. `{baseUrl}` = `http://app.k-lila.com.br` → `redirect_uri` = `http://...` → `redirect_uri mismatch` no auth-server (o registered URI é `https://...`) → sétimo elo.

- **Option B — expandir `trusted-proxies` para IPs públicos:** derrota o propósito. Não viável.

**Correção (Option C — aprovada pelo `security-reviewer`):** suprimir `X-Forwarded-For` no nginx antes de repassar ao gateway, em **todas as 9 locations**:

```nginx
# antes:
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

# depois (supressão explícita — necessária: sem ela, nginx repassa XFF que a Cloudflare envia):
proxy_set_header X-Forwarded-For "";
```

Com XFF ausente: `ForwardedHeaderTransformer` não reescreve `remoteAddress` → permanece o IP do container `interface` (172.18.0.x) → RFC1918 casa → `XForwardedHeadersFilter` emite `X-Forwarded-Host`/`Proto` → auth-server reconstrói `Location` correto. `ForwardedHeaderTransformer` ainda processa `X-Forwarded-Host` e `X-Forwarded-Proto` (vindos do nginx) → `{baseUrl}` = `https://app.k-lila.com.br` → `redirect_uri` correto. `CF-Connecting-IP` não é afetado (header customizado, não XFF).

**Supressão explícita (`""`) vs remoção da linha:** a remoção da linha não é suficiente — sem `proxy_set_header`, o nginx repassa por default qualquer XFF recebido da Cloudflare. A supressão com `""` garante que o gateway não recebe XFF independentemente do que a Cloudflare envia.

**Degradação P3 do fallback de IP (aceita, verificada pelo `security-reviewer`):** com `remoteAddress` = container `interface` (e não mais o IP reescrito via XFF), o fallback `remoteAddress.getHostString()` em `ClientIpResolver` (gateway e auth-server) passa a retornar o IP do container, colapsando todos os clientes num balde único de rate limit quando `CF-Connecting-IP` está ausente. Atrás da Cloudflare, `CF-Connecting-IP` está sempre presente — o fallback nunca é alcançado. Mesmo se alcançado, colapso num balde torna o rate limit mais grosseiro, não burlável: para ganhar granularidade um atacante precisaria forjar `CF-Connecting-IP`, que é o R-09 pré-existente e inalterado. Mesma família do BUG-002.

**Por que os testes não pegaram Elo 2 nem Elo 6:** os testes não enviam `X-Forwarded-For` (ausência do Elo 6) e não configuram `trusted-proxies` no contexto de teste (logo `XForwardedHeadersFilter` não é nem registrado). A nova classe `XForwardedHeadersIntegrationTest` reproduz a cadeia com `strategy=framework` + `trusted-proxies` (incluindo loopback para o peer de teste) e dois cenários: (a) com XFF de IP público — verifica que WireMock NÃO recebe `X-Forwarded-Host` (documenta o comportamento pré-fix e serve de alerta se nginx re-adicionar XFF); (b) sem XFF — verifica que WireMock RECEBE `X-Forwarded-Host` (verifica o estado pós-fix).

### BUG-001 (P1) — Isentar `/login` do CSRF do gateway

**Problema confirmado ao vivo (FASE 4):** sob hostname único, o nginx encaminha `POST /login` ao gateway. O formulário do auth-server embute um `_csrf` gerado pelo próprio auth-server — diferente do `XSRF-TOKEN` do gateway. O `ServerCsrfTokenRequestAttributeHandler` do gateway compara os dois; não casam; devolve **403 antes de a requisição alcançar o auth-server**. Login impossível em produção. Em dev o defeito não manifesta porque o browser vai direto a `localhost:8082/login` (porta publicada no override) sem passar pelo gateway.

**Correção:**

```java
ServerWebExchangeMatchers.pathMatchers("/v1/users/register", "/login")
```

**Escopo do matcher corrigido (BUG-003):** `pathMatchers("/login")` usa `PathPatternParserServerWebExchangeMatcher.matches()`, que compara o padrão contra `request.getPath().pathWithinApplication()` — a query string não integra o path. Portanto:
- `/login?error` — o path é `/login` → **casa** o padrão → isentado do CSRF do gateway. Mas `/login?error` é GET → já fora do `DEFAULT_CSRF_MATCHER` por método seguro; a isenção é irrelevante na prática.
- `/login/oauth2/code/gateway-client` — o path é `/login/oauth2/code/gateway-client` → **não casa** → não isentado (e é GET → já fora do `DEFAULT_CSRF_MATCHER` por método).

A isenção cobre o `POST /login` do formulário de login e, por efeito da query string excluída do path, também `/login?error` — este último já inócuo por ser GET.

**Justificativa de segurança (para o `security-reviewer`):** a proteção CSRF do gateway tem um pressuposto: o atacante explora o cookie `SESSION` do gateway, que o browser envia automaticamente numa requisição cross-origin forjada, para executar uma ação autenticada em nome da vítima. POST `/login` é **unauthenticated** — não existe sessão do gateway para forjar. O único risco residual seria "login CSRF" (forçar a vítima a autenticar com credenciais do atacante), que o auth-server já mitiga com seu próprio `_csrf` no formulário. A isenção no gateway é semanticamente idêntica à de `/v1/users/register` (ambos pré-sessão, sem estado do gateway a defender). O auth-server é o dono do CSRF do fluxo de login; o gateway apenas proxia.

### BUG-001 é instância de uma classe — enumeração de front-channels do auth-server

O BUG-001 revela uma tensão estrutural: **qualquer POST de front-channel que o browser envie ao auth-server via gateway carrega o `_csrf` gerado pelo auth-server** — valor sem relação com o `XSRF-TOKEN` do gateway, que o auth-server não tem como embutir no HTML que ele próprio renderiza. A tabela abaixo enumera todos os paths afetados por esta classe, como prevenção de redescoberta:

| Path | Método | Canal | Situação atual | Ação se ativado |
|------|--------|-------|----------------|-----------------|
| `/login` | POST | Front (browser → nginx → gateway → AS) | **Isentado** (BUG-001, esta ADR) | — |
| `/oauth2/authorize` | POST (consent) | Front | **Latente** — `requireAuthorizationConsent=false` em `OAuth2ClientConfig` (BUG-004) | Adicionar `/oauth2/authorize` à isenção de CSRF do gateway |
| `/connect/logout` | POST (confirmation) | Front | **Latente** — AS exibe tela de confirmação quando `id_token_hint` ausente/vazio (BUG-005) | Adicionar `/connect/logout` à isenção, ou garantir que `id_token_hint` seja sempre enviado |
| `/oauth2/token` | POST | Back-channel (gateway ↔ AS, sem browser) | Fora do escopo — não passa pelo browser nem pelo nginx | — |
| `/oauth2/revoke` | POST | Back-channel | Fora do escopo | — |

**BUG-004 — risco latente de CSRF em `/oauth2/authorize` (consentimento):**

`POST /oauth2/authorize` é o submit do formulário de consentimento OAuth2. Hoje é inerte porque `OAuth2ClientConfig.java` tem `requireAuthorizationConsent(false)` — confirmado no Postgres (`"settings.client.require-authorization-consent":false`). Se consentimento for habilitado no futuro, o formulário renderizado pelo auth-server não poderá carregar o `XSRF-TOKEN` do gateway, e o submit retornará 403 — a mesma colisão do BUG-001. **Ação obrigatória ao habilitar consentimento:** adicionar `/oauth2/authorize` à lista de isenção em `SecurityConfig.requireCsrfProtectionMatcher`. O teste `postOAuth2AuthorizeSemCsrf_deveRetornar403_riscoLatenteSeDomainConsentAtivado` (qa-tester, FASE 4) documenta o 403 atual e serve de sinal: quando mudar de 403 para outra coisa, a isenção está ativa.

**BUG-005 — risco latente de CSRF em `/connect/logout` (tela de confirmação):**

`GET /connect/logout?id_token_hint=<jwt>&post_logout_redirect_uri=<uri>` é o RP-Initiated Logout enviado pelo `oidcLogoutSuccessHandler`. Quando `id_token_hint` é não-vazio e válido, o Spring Authorization Server encerra a sessão do IdP sem exibir confirmação. Mas o `oidcLogoutSuccessHandler` envia string vazia quando o `authentication.getPrincipal()` não é `OidcUser` (sessão degradada, logout programático, falha de deserialização da sessão Redis). Nesse cenário, o AS pode exibir uma tela de confirmação, cujo submit é `POST /connect/logout` — que retornaria 403 por CSRF do gateway. Probabilidade baixa no fluxo normal. Dívida aceita: monitorar em upgrades do Spring Authorization Server (mudanças no comportamento da tela de confirmação). O teste `postConnectLogoutSemCsrf_deveRetornar403_riscoLatenteSemIdTokenHint` (qa-tester, FASE 4) documenta o comportamento atual.

### Decisão sobre `/error` (AC-23)

O path `/error` do auth-server **não** ganha rota no `GatewayRouter` nem `location` no nginx nesta ADR. Em erro inesperado do IdP o browser recebe `index.html` do SPA — confuso, sem implicação de segurança (`/error` é caminho de exceção raro; falha de credencial vai para `/login?error`, coberto). Registrado como **dívida aceita**. Caminho de saída: `location = /error` + rota no `GatewayRouter` + `/error` no `permitAll()`.

## Consequências

**Positivas**

- Fluxo OAuth2/BFF fecha ponta a ponta sob hostname único: "Entrar" → `/dashboard` (Elos 1–4 + 6).
- CSS do formulário do IdP servido corretamente.
- G10 fechado: premissa do ADR-010 verdadeira na topologia base — o único peer que alcança `gateway:8081` é um container da rede Docker.
- Divergência `PUBLIC_ORIGIN`/`PUBLIC_HOST` impossível de passar despercebida.
- Rate limit em `auth-login` (antes sem balde) — superfície de brute-force via formulário do IdP passa a ter controle na borda.

**Negativas / a observar**

- **Dívida de rede flat (R-09, gap aceito):** um container hostil na mesma rede Docker alcança `gateway:8081` diretamente e pode forjar `X-Forwarded-*`, independentemente do `trusted-proxies` e do G10. Resíduo pré-existente — ADR-010 não cobre a rede interna. Registrado em `docs/SECURITY.md`.
- **Nota CGNAT:** rate limit MED por-IP em `auth-login` compartilha balde sob CGNAT — risco pré-existente idêntico às rotas `/oauth2/**`.
- **`/error` sem rota:** em erro inesperado do IdP o browser recebe `index.html`. Dívida aceita (raro, sem implicação de segurança).
- **Regressão de upgrade a documentar:** a ausência de `trusted-proxies` no SCG 5.0.0 não manifesta em dev nem no CI (que rodam sem nginx/Cloudflare). Armadilha de upgrade documentada aqui para consumidores do blueprint.
- **`Forwarded` (RFC 7239) emitido:** todas as requests downstream passam a carregar o header `Forwarded`. Nenhum serviço downstream o lê hoje; efeito prático nulo, mas documentado.
- **BUG-002 — `X-Forwarded-For` não propagado ao auth-server (dívida aceita, P3):** com `for-enabled: false`, o gateway não repassa `X-Forwarded-For` downstream. Em deploy **sem** Cloudflare, o fallback `getRemoteAddr()` do `ClientIpResolver` do auth-server devolve o IP do container do gateway — todos os clientes colapsam num único balde para o lockout anti-brute-force (efetivamente desabilitando o lockout por-usuário no auth-server). Verificado pelo qa-tester: não é regressão nova — o comportamento existia desde antes desta ADR, quando o `XForwardedHeadersFilter` deixou de ser registrado por default no SCG 5.0.0. Na topologia de deploy atual (Cloudflare Tunnel), o `CF-Connecting-IP` é o controle real de IP e não é afetado por esta configuração. A dívida só morde em deploy direto sem Cloudflare — contexto fora do blueprint atual.

## Alternativas consideradas

- **Rota `/login` sem rate limit:** descartada — path público e unauthenticated na borda, sem balde vira alvo gratuito de flood contra o auth-server.
- **Subnet Docker fixa em `trusted-proxies`:** descartada — subnet `user-service-net` não é fixa no compose; RFC1918 amplo é machine-agnostic sem perda de segurança com G10 fechado.
- **Expor auth-server num segundo hostname:** descartada — reintroduz CORS, espalha cookies por dois hosts, amplia superfície pública. Hostname único é justamente o que simplifica cookies/CORS.
- **`assert-env` em `docker-compose.yml` (base):** descartada — correria também em dev (onde `PUBLIC_ORIGIN`/`PUBLIC_HOST` são opcionais), e quebraria `compose-validate` se as variáveis não estivessem definidas.
