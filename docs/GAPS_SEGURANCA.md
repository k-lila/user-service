# Gaps de Segurança Conhecidos

> Extraído do `CLAUDE.md` para reduzir seu tamanho. Visão geral do projeto em [../CLAUDE.md](../CLAUDE.md).

## Índice

- [Como ler](#como-ler)
- [Resumo](#resumo)
- [Detalhamento dos gaps](#detalhamento-dos-gaps)
  - [G3 — config-server sem autenticação](#g3--config-server-sem-autenticação)
- [Controles de segurança já implementados](#controles-de-segurança-já-implementados)

## Como ler

- **Severidade** calibrada para o alvo do projeto: **produção real, multi-instância**.
- **Status:** _Aberto_ (a tratar) · _Aceito_ (decisão registrada de conviver com o gap) · _Curativo_ (mitigação parcial aplicada). Gaps **resolvidos** saem desta lista (ver breadcrumb abaixo).
- **Ref** aponta o item correlato em [TRABALHO_PENDENTE.md](TRABALHO_PENDENTE.md) (correções `C#` / temas `§`), que detalha o plano de correção.

## Resumo

| #   | Gap                                                                            | Localização                                                       | Severidade | Status   | Ref |
| --- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------- | ---------- | -------- | --- |
| G1  | Sem TLS/HTTPS (cookies de sessão sem flag `Secure` por consequência)           | Todo o sistema                                                   | Alta       | Aberto   | §3  |
| G3  | config-server sem autenticação serve YAMLs com secrets default                 | config-server + porta `8888` publicada                          | Média      | Curativo (C17) | C17 |
| G5  | Chave privada JWK **dev** rastreada no classpath                               | `authorization-server/.../keys/app.key`                         | Média      | Aceito   | §1  |
| G7  | CORS duplicado e hardcoded em 3 módulos                                        | `CORSConfig.java` (gateway / user-service / auth-server)         | Média      | Curativo | C12 |
| G9  | Validação de senha fraca e dividida (sem complexidade, nullable)               | `UserRequestDTO.java:21` + `RegisterService`                    | Baixa      | Aberto   | C13 |
| G11 | Grafana `admin/admin`                                                          | `docker-compose.yml` (`GF_SECURITY_ADMIN_*`)                    | Baixa      | Curativo (C11) | C11 |
| G12 | Keyfile MongoDB de dev rastreado no repositório                               | `infra/mongo/keyfile`                                           | Média      | Aceito   | —   |

> **Resolvidos (removidos desta lista):** G2 (C16), G4 (C11), G6 (C18), G8 (C8) e G10 (C19), além do JWT em `localStorage` no front-end (BFF). Os IDs são preservados (a numeração não é reusada); detalhes em [TRABALHO_PENDENTE.md](TRABALHO_PENDENTE.md) e no histórico git. Os controles ativos correspondentes estão em [Controles de segurança já implementados](#controles-de-segurança-já-implementados).

**Notas dos gaps diretos (sem subseção):**

- **G1 — TLS/HTTPS:** decidido configurar junto com a infra de produção. Sem TLS, os cookies `SESSION`/`AUTHSESSION` saem sem a flag `Secure` (hoje só `HttpOnly` + `SameSite=Lax`) — resolve-se com o TLS.
- **G5 — Chave JWK dev:** **Aceito (Opção A)** — par RSA dev fixo no classpath para o `docker compose up` funcionar sem setup. Em produção, **sobrescrever** via `JWK_PRIVATE_KEY`/`JWK_PUBLIC_KEY`/`JWK_KEY_ID` apontando para secret montado (`file:/run/secrets/...`); a chave dev nunca vai para produção. Ver §1.
- **G7 — CORS:** `CorsFilter`/`CorsConfigurationSource` repetido nos 3 módulos com origens fixas; já causou `403 Invalid CORS request` + `vary` duplicado. Curativo: `localhost:5173` na allowlist do user-service. Alvo: CORS só na borda (gateway) e configurável por ambiente. Ver C12.
- **G9 — Senha:** `@Size(min=8)` é nullable (sem `@NotBlank`) e a ausência é checada manualmente no `RegisterService`; sem regra de complexidade. Unificar na validação declarativa. Ver C13.
- **G11 — Grafana:** **Curativo (C11).** Credenciais externalizadas para `.env` (`GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`) — não mais hardcoded no compose versionado; o valor de dev segue `admin/admin`, **trocar em produção**. Prometheus `:9090` e Grafana `:3000` não publicados em prod (via C16); auth na borda resolve com TLS. Ver C11.
- **G12 — Keyfile MongoDB:** `infra/mongo/keyfile` (string base64 aleatória) está rastreado no repositório para permitir o `docker compose up` sem setup externo. **Aceito (Opção A)** — padrão idêntico ao G5 (chave JWK dev no classpath); em produção, montar o keyfile via secret externo e remover o arquivo do repo (ou adicionar ao `.gitignore`).

## Detalhamento dos gaps

### G3 — config-server sem autenticação

- **Risco:** o config-server não tem Spring Security; qualquer um que alcance a porta lê a configuração de todos os serviços. Os YAMLs trazem os **defaults de secret** embutidos em `${VAR:default}` (ex.: `auth_1234321`, `gateway-secret`), então a resposta divulga credenciais de dev.
- **Evidência:** ausência de dependência/`SecurityFilterChain` no config-server; `ports: "8888:8888"` no compose; defaults em `config-server/.../config/*.yml`.
- **Mitigação alvo:** não publicar a porta `8888` em produção; proteger o endpoint (Spring Security Basic/mTLS) e remover os defaults de secret dos YAMLs (forçar injeção por env/secret).
- **Status — Curativo (C17):** porta `8888` (`config-lb`) **não publicada** em prod (via C16) e **defaults de secret removidos** dos YAMLs servidos — `AUTH_DB_USER`/`AUTH_DB_PASSWORD` (`authorization-server.yml`) e `OAUTH_CLIENT_SECRET` ×2 (`gateway.yml`) viraram `${VAR}` sem fallback, então a env ausente no cliente derruba a subida (fail-fast). **Pendente:** autenticação no endpoint do config-server (Basic/mTLS) — nesta rodada confia-se no isolamento de rede.

## Controles de segurança já implementados

Para contexto — o básico de segurança da base já está coberto:

- **BFF:** o token **nunca** toca o browser (fica na sessão do gateway); o SPA usa só cookie `HttpOnly`. Elimina de raiz o XSS exfiltrar JWT/refresh.
- **CSRF no gateway:** `CookieServerCsrfTokenRepository` (cookie `XSRF-TOKEN`), com `/users/register` isento; entry point devolve 401 (não 302).
- **Canal interno blindado:** `InternalTokenFilter` exige `X-Internal-Token` e compara em **tempo constante** (`MessageDigest.isEqual`); acesso direto a `/internal/**` sem o header → 403.
- **Senhas:** BCrypt (custo padrão 10).
- **OAuth2:** PKCE (S256) no `oauth2Login` do gateway; validação JWT **stateless** no resource server.
- **Multi-instância:** sessão no Redis (Spring Session) e estado OAuth em Postgres — sem estado local de processo.
- **Portas internas fechadas (C16):** em prod o compose publica só a borda; serviços internos ficam na rede privada.
- **Lockout anti-brute-force (C19):** contador de falhas no Redis por (conta, IP), lockout após 5 falhas, mensagem genérica.
- **Autorização por roles (C8):** `permissions` do JWT derivadas das roles (`USER`/`ADMIN`), não mais hardcoded.
