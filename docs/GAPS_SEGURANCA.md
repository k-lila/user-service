# Gaps de Segurança Conhecidos

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler](#como-ler)
- [Resumo](#resumo)
- [Controles de segurança já implementados](#controles-de-segurança-já-implementados)

## Como ler

- **Severidade** calibrada para o alvo do projeto: **produção real, multi-instância**.
- **Status:** _Aberto_ (a tratar) · _Aceito_ (decisão registrada de conviver com o gap) · _Curativo_ (mitigação parcial aplicada). Gaps **resolvidos** saem desta lista (ver breadcrumb abaixo).
- **Ref** aponta o item correlato em [TRABALHO_PENDENTE.md](TRABALHO_PENDENTE.md) (correções `C#`), que detalha o plano de correção; "—" indica gap sem plano aberto lá (decisão/risco descritos na nota do próprio gap).

## Resumo

| #   | Gap                                                                            | Localização                                                       | Severidade | Status   | Ref |
| --- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------- | ---------- | -------- | --- |
| G1  | Sem TLS/HTTPS (cookies de sessão sem flag `Secure` por consequência)           | Todo o sistema                                                   | Alta       | Curativo (dev) | —   |
| G5  | Chave privada JWK **dev** rastreada no classpath                               | `authorization-server/.../keys/app.key`                         | Média      | Aceito   | —   |
| G11 | Grafana `admin/admin`                                                          | `docker-compose.yml` (`GF_SECURITY_ADMIN_*`)                    | Baixa      | Curativo (C11) | —   |
| G12 | Keyfile MongoDB de dev rastreado no repositório                               | `infra/mongo/keyfile`                                           | Média      | Aceito   | —   |
| G13 | Redis/Sentinel sem autenticação (`requirepass`/ACL)                            | `docker-compose.yml` (redis-1/2/3 + sentinels)                  | Média      | Aberto   | —   |

> **Resolvidos (removidos desta lista):** G2 (C16), G3 (C17), G4 (C11), G6 (C18), G7 (C12), G8 (C8), G9 (C13) e G10 (C19), além do JWT em `localStorage` no front-end (BFF). Os IDs são preservados (a numeração não é reusada); detalhes no histórico git. Os controles ativos correspondentes estão em [Controles de segurança já implementados](#controles-de-segurança-já-implementados).

**Notas dos gaps diretos (sem subseção):**

- **G1 — TLS/HTTPS:** **Curativo (dev) — terminação TLS na borda via reverse-proxy nginx + mkcert.** Overlay opt-in `docker-compose.tls.yml` sobe um `tls-proxy` que fala HTTPS com o browser (`https://app.localhost` = SPA+BFF, `https://auth.localhost` = front-channel OAuth2) e mantém o tráfego interno em HTTP — topologia idêntica à de prod (ir para prod = trocar o cert mkcert→ACME e os hostnames `*.localhost` por domínios reais). Com TLS ligado, os cookies `SESSION`/`AUTHSESSION`/`XSRF-TOKEN` saem com **`Secure`** (gateway via `app.cookie.secure`/`APP_COOKIE_SECURE`; auth-server via `server.forward-headers-strategy`). O Swagger-UI (`http://localhost:8081/swagger-ui`) segue funcional no modo TLS: o fluxo OAuth2 passa pela borda (`AUTH_URL`/`AUTH_TOKEN` → `https://auth.localhost`) e o CORS do auth-server inclui a origem do Swagger. Setup e execução em [TLS_DEV.md](TLS_DEV.md). **Pendência:** TLS de produção real (cert ACME/corporativo + domínios) segue para a infra de deploy; o `docker compose up` padrão (sem o overlay) continua HTTP puro.
- **G5 — Chave JWK dev:** **Aceito (Opção A)** — par RSA dev fixo no classpath para o `docker compose up` funcionar sem setup. Em produção, **sobrescrever** via `JWK_PRIVATE_KEY`/`JWK_PUBLIC_KEY`/`JWK_KEY_ID` apontando para secret montado (`file:/run/secrets/...`); a chave dev nunca vai para produção.
- **G11 — Grafana:** **Curativo (C11).** Credenciais externalizadas para `.env` (`GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`) — não mais hardcoded no compose versionado; o valor de dev segue `admin/admin`, **trocar em produção**. Prometheus `:9090` e Grafana `:3000` não publicados em prod (via C16); auth na borda resolve com TLS.
- **G12 — Keyfile MongoDB:** `infra/mongo/keyfile` (string base64 aleatória) está rastreado no repositório para permitir o `docker compose up` sem setup externo. **Aceito (Opção A)** — padrão idêntico ao G5 (chave JWK dev no classpath); em produção, montar o keyfile via secret externo e remover o arquivo do repo (ou adicionar ao `.gitignore`).
- **G13 — Redis/Sentinel sem autenticação:** os nós `redis-1/2/3` e os três Sentinels sobem sem `requirepass`/ACL, e os clientes Spring não enviam `spring.data.redis.password`. O Redis guarda dados sensíveis: **sessões** do gateway e do auth-server (a do gateway contém o `OAuth2AuthorizedClient` — JWT e refresh token), **caches** do user-service (`authByEmail` inclui o hash BCrypt da senha) e os contadores de rate-limit/lockout (um atacante com acesso poderia zerar o lockout do C19). **Mitigação atual:** as portas 6379/26379 **nunca são publicadas** — nem no compose base nem no override de dev — então só quem está na rede interna do Docker alcança o Redis. É o mesmo padrão do config-server pré-C17 (isolamento de rede como única camada), que o projeto decidiu reforçar; pela mesma régua, cabe `requirepass` + `masterauth` (réplicas) + `sentinel auth-pass` + senha via env nos clientes. Não bloqueia nada hoje — defesa em profundidade alinhada ao alvo "produção real".

## Controles de segurança já implementados

Para contexto — o básico de segurança da base já está coberto:

- **BFF:** o token **nunca** toca o browser (fica na sessão do gateway); o SPA usa só cookie `HttpOnly`. Elimina de raiz o XSS exfiltrar JWT/refresh.
- **CSRF no gateway:** `CookieServerCsrfTokenRepository` (cookie `XSRF-TOKEN`), com `/users/register` isento; entry point devolve 401 (não 302).
- **Canal interno blindado:** `InternalTokenFilter` exige `X-Internal-Token` e compara em **tempo constante** (`MessageDigest.isEqual`); acesso direto a `/internal/**` sem o header → 403.
- **Senhas:** BCrypt (custo padrão 10); validação declarativa unificada (C13) — 8–72 chars (limite do BCrypt) com ao menos uma letra e um número, obrigatória no registro (`@NotBlank` no grupo `OnCreate`) e opcional no update (null = mantém a atual).
- **OAuth2:** PKCE (S256) no `oauth2Login` do gateway; validação JWT **stateless** no resource server.
- **Multi-instância:** sessão no Redis (Spring Session) e estado OAuth em Postgres — sem estado local de processo.
- **Portas internas fechadas (C16):** em prod o compose publica só a borda; serviços internos ficam na rede privada.
- **Config-server protegido (C17):** porta `8888` não publicada em prod (C16) + **HTTP Basic** no endpoint (`/actuator/health` aberto p/ healthchecks) + defaults de secret removidos dos YAMLs servidos. Os clientes autenticam via `spring.cloud.config.username/password`.
- **CORS na borda + configurável (C12):** `CORSConfig` removido do **user-service** (nunca recebe fetch cross-origin — só via gateway). O **gateway** faz CORS para o SPA (`CORS_ALLOWED_ORIGINS`, default `localhost:5173`); o **auth-server** mantém CORS para o **Swagger-UI** (cliente OAuth2 no browser que faz fetch cross-origin a `/oauth2/token`) com origem própria (`CORS_ALLOWED_ORIGINS_AUTH`, default `localhost:8081`). Ambos via `setAllowedOriginPatterns` (compatível com `allowCredentials`).
- **Lockout anti-brute-force (C19):** contador de falhas no Redis por (conta, IP), lockout após 5 falhas, mensagem genérica.
- **Autorização por roles (C8):** `permissions` do JWT derivadas das roles (`USER`/`ADMIN`), não mais hardcoded.
- **TLS na borda em dev (G1, curativo):** overlay opt-in `docker-compose.tls.yml` (nginx + mkcert) termina TLS na borda; cookies de sessão com flag `Secure` exercíveis localmente. Mesma topologia da prod — ver [TLS_DEV.md](TLS_DEV.md).
