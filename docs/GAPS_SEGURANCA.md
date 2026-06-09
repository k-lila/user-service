# Gaps de Segurança Conhecidos

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler](#como-ler)
- [Resumo](#resumo)
- [Detalhamento dos gaps](#detalhamento-dos-gaps)
  - [G2 — Portas internas publicadas no host](#g2--portas-internas-publicadas-no-host)
  - [G3 — config-server sem autenticação](#g3--config-server-sem-autenticação)
  - [G6 — Actuator exposto sem auth na borda](#g6--actuator-exposto-sem-auth-na-borda)
  - [G8 — Autorização grosseira (`permissions` hardcoded)](#g8--autorização-grosseira-permissions-hardcoded)
  - [G10 — Sem proteção a brute-force no login](#g10--sem-proteção-a-brute-force-no-login)
  - [G12 — Keyfile MongoDB de dev rastreado no repositório](#g12--keyfile-mongodb-de-dev-rastreado-no-repositório)
- [Controles de segurança já implementados](#controles-de-segurança-já-implementados)

## Como ler

- **Severidade** calibrada para o alvo do projeto: **produção real, multi-instância**.
- **Status:** _Aberto_ (a tratar) · _Aceito_ (decisão registrada de conviver com o gap) · _Curativo_ (mitigação parcial aplicada) · _Resolvido_.
- **Ref** aponta o item correlato em [TRABALHO_PENDENTE.md](TRABALHO_PENDENTE.md) (correções `C#` / temas `§`), que detalha o plano de correção.

## Resumo

| #   | Gap                                                                            | Localização                                                       | Severidade | Status   | Ref |
| --- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------- | ---------- | -------- | --- |
| G1  | Sem TLS/HTTPS (cookies de sessão sem flag `Secure` por consequência)           | Todo o sistema                                                   | Alta       | Aberto   | §3  |
| G2  | Portas internas publicadas no host → acesso direto contorna o gateway          | `docker-compose.yml` (`8090`, `8082`, `8888`, `9091`)            | Alta       | **Resolvido** (C16) | C16 |
| G3  | config-server sem autenticação serve YAMLs com secrets default                 | config-server + porta `8888` publicada                          | Média      | Curativo (C17) | C17 |
| G4  | Secrets em claro (Mongo, Postgres, `OAUTH_CLIENT_SECRET`, internal token)      | `docker-compose.yml`                                            | Média      | **Resolvido** (C11) | C11 |
| G5  | Chave privada JWK **dev** rastreada no classpath                               | `authorization-server/.../keys/app.key`                         | Média      | Aceito   | §1  |
| G6  | Actuator exposto sem auth na borda pública (`/actuator/prometheus`, `/metrics`) | `gateway/.../SecurityConfig.java` + `gateway.yml`               | Média      | **Resolvido** (C18) | C18 |
| G7  | CORS duplicado e hardcoded em 3 módulos                                        | `CORSConfig.java` (gateway / user-service / auth-server)         | Média      | Curativo | C12 |
| G8  | Autorização grosseira: `permissions` hardcoded para todo usuário               | `TokenCustomizerConfig.java`                                    | Média      | **Resolvido** (C8) | C8  |
| G9  | Validação de senha fraca e dividida (sem complexidade, nullable)               | `UserRequestDTO.java:21` + `RegisterService`                    | Baixa      | Aberto   | C13 |
| G10 | Sem proteção a brute-force / lockout de conta no login                         | auth-server (form login) + rate limit do gateway                | Média      | **Resolvido** (C19) | C19 |
| G11 | Grafana `admin/admin`                                                          | `docker-compose.yml` (`GF_SECURITY_ADMIN_*`)                    | Baixa      | Curativo (C11) | C11 |
| G12 | Keyfile MongoDB de dev rastreado no repositório                                | `infra/mongo/keyfile`                                           | Média      | Aceito   | —   |
| —   | JWT armazenado em `localStorage` no front-end                                  | `login-interface/`                                              | —          | **Resolvido** (BFF) | — |

**Notas dos gaps diretos (sem subseção):**

- **G1 — TLS/HTTPS:** decidido configurar junto com a infra de produção. Sem TLS, os cookies `SESSION`/`AUTHSESSION` saem sem a flag `Secure` (hoje só `HttpOnly` + `SameSite=Lax`) — resolve-se com o TLS.
- **G4 — Secrets em claro:** **Resolvido (C11).** Mongo, Postgres, `OAUTH_CLIENT_SECRET` (incl. o que estava hardcoded no gateway) e `INTERNAL_API_TOKEN` movidos para `.env` git-ignored, referenciados por `${VAR}` **sem default** (a subida falha alto se faltar `.env`). Template versionado em `.env.example`. Em produção, injetar via secret manager. Ver C11.
- **G5 — Chave JWK dev:** **Aceito (Opção A)** — par RSA dev fixo no classpath para o `docker compose up` funcionar sem setup. Em produção, **sobrescrever** via `JWK_PRIVATE_KEY`/`JWK_PUBLIC_KEY`/`JWK_KEY_ID` apontando para secret montado (`file:/run/secrets/...`); a chave dev nunca vai para produção. Ver §1.
- **G7 — CORS:** `CorsFilter`/`CorsConfigurationSource` repetido nos 3 módulos com origens fixas; já causou `403 Invalid CORS request` + `vary` duplicado. Curativo: `localhost:5173` na allowlist do user-service. Alvo: CORS só na borda (gateway) e configurável por ambiente. Ver C12.
- **G9 — Senha:** `@Size(min=8)` é nullable (sem `@NotBlank`) e a ausência é checada manualmente no `RegisterService`; sem regra de complexidade. Unificar na validação declarativa. Ver C13.
- **G11 — Grafana:** **Curativo (C11).** Credenciais externalizadas para `.env` (`GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`) — não mais hardcoded no compose versionado; o valor de dev segue `admin/admin`, **trocar em produção**. Prometheus `:9090` e Grafana `:3000` ainda sem auth na borda (resolve com C16/TLS). Ver C11.
- **G12 — Keyfile MongoDB:** `infra/mongo/keyfile` (string base64 aleatória) está rastreado no repositório para permitir o `docker compose up` sem setup externo. **Aceito (Opção A)** — padrão idêntico ao G5 (chave JWK dev no classpath); em produção, montar o keyfile via secret externo e remover o arquivo do repo (ou adicionar ao `.gitignore`).

## Detalhamento dos gaps

### G2 — Portas internas publicadas no host

- **Risco:** o `docker-compose.yml` publica as portas dos serviços internos no host, permitindo chamá-los **sem passar pelo gateway** — o que contorna o rate limiting e o CORS da borda. Ex.: `POST localhost:8090/users/register` ignora o limite de 2 req/s (por IP) aplicado no gateway.
- **Evidência:** `ports: "8090:8090"` (user-service), `"8082:8082"` (auth-server), `"8888:8888"` (config-server), `"9091:9091"` (discovery).
- **Mitigação alvo:** em produção, expor **apenas o gateway** (e o auth-server no que o browser precisa do front-channel); manter os demais só na rede interna (sem `ports:`), confiando no isolamento de rede + JWT. O `8082` é exceção parcial: o browser precisa do `/oauth2/authorize` e `/connect/logout` — expor só esses caminhos, não a porta inteira.
- **Status — Resolvido (C16):** `docker-compose.yml` virou **base prod-safe** (publica só `gateway:8081` e `interface`); todos os serviços internos — incl. o auth-server — ficaram **sem `ports:`**. `docker-compose.override.yml` (auto-carregado) republica as portas em **dev**. Prod roda com `docker compose -f docker-compose.yml up`. A exposição restrita do front-channel do auth-server (`/oauth2/authorize`, `/login`, `/connect/logout`) num hostname dedicado é delegada ao reverse-proxy de TLS/ingress (G1).

### G3 — config-server sem autenticação

- **Risco:** o config-server não tem Spring Security; qualquer um que alcance a porta lê a configuração de todos os serviços. Os YAMLs trazem os **defaults de secret** embutidos em `${VAR:default}` (ex.: `auth_1234321`, `gateway-secret`), então a resposta divulga credenciais de dev.
- **Evidência:** ausência de dependência/`SecurityFilterChain` no config-server; `ports: "8888:8888"` no compose; defaults em `config-server/.../config/*.yml`.
- **Mitigação alvo:** não publicar a porta `8888` em produção; proteger o endpoint (Spring Security Basic/mTLS) e remover os defaults de secret dos YAMLs (forçar injeção por env/secret). Encadeia com G2 e G4.
- **Status — Curativo (C17):** porta `8888` (`config-lb`) **não publicada** em prod (via C16) e **defaults de secret removidos** dos YAMLs servidos — `AUTH_DB_USER`/`AUTH_DB_PASSWORD` (`authorization-server.yml`) e `OAUTH_CLIENT_SECRET` ×2 (`gateway.yml`) viraram `${VAR}` sem fallback, então a env ausente no cliente derruba a subida (fail-fast). **Pendente:** autenticação no endpoint do config-server (Basic/mTLS) — nesta rodada confia-se no isolamento de rede.

### G6 — Actuator exposto sem auth na borda

- **Risco:** no gateway (ponto de entrada externo, porta `8081`), `/actuator/**` é `permitAll`. `/actuator/prometheus` e `/actuator/metrics` ficam acessíveis sem autenticação → divulgação de métricas operacionais (URIs, contadores, latências).
- **Evidência:** `gateway/.../SecurityConfig.java` lista `/actuator/**` em `permitAll`; `gateway.yml` expõe `health, info, metrics, prometheus`. _(O endpoint `gateway` de rotas está `enabled` mas **não** consta no `exposure.include`, então não é servido.)_
- **Mitigação alvo:** restringir o `exposure.include` ao mínimo na borda (idealmente só `health`) e exigir autenticação para `metrics`/`prometheus`, ou raspar métricas por uma rede/porta de management interna não publicada.
- **Status — Resolvido (C18):** actuator do gateway movido para `management.server.port: 8181` (porta de management **interna**, não publicada no host). A borda externa (8081) deixa de servir `/actuator/**`; o Prometheus raspa por `gateway:8181` na rede interna e o healthcheck do container aponta para `8181`. _(O `/actuator/**` em `permitAll` no `SecurityConfig` do gateway virou config morta — removível em higiene futura.)_

### G8 — Autorização grosseira (`permissions` hardcoded)

- **Risco:** o claim `permissions` do JWT é fixo para **todo** usuário, inclusive ADMIN — a autorização fina por permissão não reflete a role real. Se algum recurso passar a confiar em `permissions`, o controle de acesso fica incorreto.
- **Evidência (original):** `TokenCustomizerConfig` injetava `["users.read","users.write"]` para qualquer login.
- **Mitigação alvo:** derivar `permissions` das `roles` (ex.: ADMIN ganha `users.delete`). Ver C8. _(A autorização efetiva das rotas usa `roles` via `@PreAuthorize`/`ROLE_`, então o impacto era latente, não explorável.)_
- **Status — Resolvido (C8):** `TokenCustomizerConfig` deriva `permissions` de `user.getRoles()` — `USER` → `users.read`/`users.write`; `ADMIN` adiciona `users.delete` (`LinkedHashSet` deduplica; `new ArrayList<>(...)` preservado para a serialização do SAS no Postgres).

### G10 — Sem proteção a brute-force no login

- **Risco:** o login no auth-server (form login) não tem lockout nem backoff por conta; a única barreira é o rate limit por IP no gateway (MED, 5 req/s). Um atacante distribuído (vários IPs) ou com baixa taxa por IP pode tentar senhas sem bloqueio de conta.
- **Evidência (original):** `authorization-server/.../SecurityConfig.java` usa `formLogin(Customizer.withDefaults())` sem listener de bloqueio; rate limit só por IP no gateway.
- **Mitigação alvo:** lockout/backoff por conta após N falhas (ex.: contador no Redis, já disponível), CAPTCHA após limite, e alertas. Encaixa com a Auditoria de eventos (TRABALHO_PENDENTE §5).
- **Status — Resolvido (C19):** contador de falhas no Redis por par **(conta, IP)**, janela fixa (TTL 15 min na 1ª falha), lockout após **5 falhas**. `LoginAttemptService`/`LoginAttemptListener` (conta só `AuthenticationFailureBadCredentialsEvent`) + `accountNonLocked=false` no `AuthorizationService` → `LockedException` antes da checagem de senha. Mensagem genérica (sem enumeração); chave `sha256(emailLower|ip)`. CAPTCHA e auditoria de eventos seguem como evolução. **Prod:** exige `server.forward-headers-strategy` + proxy sobrescrevendo `X-Forwarded-For` para o IP por trás do proxy não colapsar (header spoofável se confiado sem sanitização).

## Controles de segurança já implementados

Para contexto — o básico de segurança da base já está coberto:

- **BFF:** o token **nunca** toca o browser (fica na sessão do gateway); o SPA usa só cookie `HttpOnly`. Elimina de raiz o XSS exfiltrar JWT/refresh.
- **CSRF no gateway:** `CookieServerCsrfTokenRepository` (cookie `XSRF-TOKEN`), com `/users/register` isento; entry point devolve 401 (não 302).
- **Canal interno blindado:** `InternalTokenFilter` exige `X-Internal-Token` e compara em **tempo constante** (`MessageDigest.isEqual`); acesso direto a `/internal/**` sem o header → 403.
- **Senhas:** BCrypt (custo padrão 10).
- **OAuth2:** PKCE (S256) no `oauth2Login` do gateway; validação JWT **stateless** no resource server.
- **Multi-instância:** sessão no Redis (Spring Session) e estado OAuth em Postgres — sem estado local de processo.
