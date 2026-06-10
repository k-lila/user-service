# TLS/HTTPS na borda — ambiente de desenvolvimento

> Fecha (curativo) o gap **G1**. Sobe uma terminação TLS na **borda** em dev, com a
> **mesma topologia da produção**: um reverse-proxy fala HTTPS com o browser e o
> tráfego interno segue HTTP na rede do Docker. Ir para produção = trocar o
> certificado do mkcert por um ACME/corporativo e os hostnames `*.localhost` por
> domínios reais — a topologia e o código não mudam.

## Visão geral

```
BROWSER ──https://app.localhost──┐        BROWSER ──https://auth.localhost──┐
                                 ▼                                          ▼
                       ┌──────────── tls-proxy (nginx, :443, mkcert) ────────────┐
                       │  app.localhost  → interface:80  (SPA + proxy BFF → gateway)│
                       │  auth.localhost → authorization-server:8082 (front-channel)│
                       └───────────────────────┬──────────────────────────────────┘
                                               │ HTTP interno (sem TLS)
```

- **`app.localhost`** — SPA + rotas BFF (`/users`, `/oauth2`, `/login/oauth2`, `/logout`).
- **`auth.localhost`** — front-channel OAuth2 do auth-server (`/oauth2/authorize`, `/login`, `/connect/logout`).
- `*.localhost` resolve para `127.0.0.1` automaticamente nos navegadores — **sem editar `/etc/hosts`**.
- O back-channel (gateway → auth-server: discovery/token/jwks) **continua HTTP interno** (`authorization-server:8082`) — **sem truststore Java**.

## Pré-requisito: gerar o certificado (uma vez)

1. **Instalar o mkcert** (se ainda não tiver):

   ```bash
   # Debian/Ubuntu
   sudo apt install libnss3-tools
   # binário: https://github.com/FiloSottile/mkcert/releases  (ou: brew install mkcert)
   ```

2. **Instalar a CA local** no trust store do SO/navegador (cadeado verde, sem aviso):

   ```bash
   mkcert -install
   ```

3. **Emitir o cert da borda** (um cert com SANs para os dois hosts), na raiz do repo:

   ```bash
   mkcert -cert-file infra/tls-proxy/certs/edge.crt \
          -key-file  infra/tls-proxy/certs/edge.key \
          app.localhost auth.localhost localhost 127.0.0.1
   ```

   Os arquivos `edge.crt`/`edge.key` ficam em `infra/tls-proxy/certs/` e são
   **git-ignored** (nunca versionar chave privada).

## Subir com TLS

```bash
docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
```

- Combina a **base prod-safe** com o **overlay TLS** e **não** carrega o
  `docker-compose.override.yml` — então só a borda (`:443`/`:80`) fica exposta,
  espelhando produção.
- **1ª vez (re-seed):** o `gateway-client` é semeado de forma idempotente e persiste
  no Postgres. Como o registro das `redirectUri` de `https://app.localhost` é novo,
  recrie o volume do banco OAuth na primeira subida com TLS:

  ```bash
  docker compose -f docker-compose.yml -f docker-compose.tls.yml down
  docker volume rm user-service_auth_db_data   # confira o nome exato com: docker volume ls
  docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
  ```

Acesso: **https://app.localhost** (SPA + API) · **https://auth.localhost** (front-channel OAuth2).

## Verificação

1. **Cadeado válido:** abrir `https://app.localhost` → HTTPS sem aviso (CA do mkcert confiável).
2. **Fluxo OAuth2/BFF:** Login → redireciona a `https://auth.localhost/oauth2/authorize`
   → form → callback `https://app.localhost/login/oauth2/code/gateway-client` →
   `GET /users/me` retorna **200**.
3. **Flag `Secure` (o alvo do G1):** DevTools → Application → Cookies:
   - `SESSION` e `AUTHSESSION` → **`Secure ✔` + `HttpOnly ✔`**.
   - `XSRF-TOKEN` → **`Secure ✔`** (`HttpOnly ✘`, o SPA precisa lê-lo).
4. **Smoke test via CLI** (sem browser):
   ```bash
   curl -sk -o /dev/null -w "%{http_code}\n" --resolve app.localhost:443:127.0.0.1  https://app.localhost/        # 200
   curl -sk -o /dev/null -w "%{http_code}\n" --resolve auth.localhost:443:127.0.0.1 https://auth.localhost/login  # 200
   curl -sk -o /dev/null -w "%{http_code}\n" --resolve app.localhost:443:127.0.0.1  https://app.localhost/users/me # 401 (sem sessão)
   ```
5. **Lockout C19 atrás do proxy:** 5 logins falhos seguidos → bloqueio (`LockedException`),
   contando pelo **IP real** (via `X-Forwarded-For`), não pelo IP do proxy.
6. **Rate limit na borda:** burst de `POST /users/register` → **429** (não 500) e
   `WARN | 429 | rate limit excedido` no log do gateway com o IP real do cliente.
7. **Swagger-UI no modo TLS:** `http://localhost:8081/swagger-ui` → Authorize →
   redireciona a `https://auth.localhost/oauth2/authorize` → login → token via fetch
   cross-origin ao `https://auth.localhost/oauth2/token` (CORS liberado para
   `http://localhost:8081`). Sem o override de dev a porta 8082 **não** é publicada —
   o fluxo passa inteiro pela borda.

> **Porta 80 / redirect HTTP→HTTPS:** o `tls-proxy` publica **só a 443**. A 80 não é
> publicada porque é comum a porta 80 do host já estar ocupada (Apache/nginx local), e o
> conflito quebra a criação do container. Acesse sempre `https://` direto. Para ter o
> redirect `http→https`, libere a 80 no host (ex.: `sudo systemctl stop apache2`) e
> re-adicione `"80:80"` ao `tls-proxy` em `docker-compose.tls.yml`.

## Como funciona (peças)

| Peça | Papel |
| --- | --- |
| `infra/tls-proxy/{nginx.conf,Dockerfile}` | Reverse-proxy que termina TLS; 2 server blocks (`app`/`auth`) + redirect `80→443`. |
| `infra/tls-proxy/certs/` | Cert do mkcert (`edge.crt`/`edge.key`), git-ignored. |
| `docker-compose.tls.yml` | Overlay opt-in: serviço `tls-proxy` + envs HTTPS de front-channel/cookies + Swagger via borda (`AUTH_URL`/`AUTH_TOKEN` do user-service e CORS do auth-server com `localhost:8081`). |
| `OAuth2ClientConfig.java` | Registra `redirectUri`/`postLogoutRedirectUri` de `https://app.localhost`. |
| `login-interface/nginx.conf` | Pass-through do `X-Forwarded-Proto` (preserva o `https` da borda). |
| `gateway/.../SecurityConfig.java` | `app.cookie.secure` (`APP_COOKIE_SECURE`) → `Secure` em `SESSION`/`XSRF-TOKEN`. |
| `gateway/.../RateLimiterConfig.java` + `RateLimitLogFilter.java` | Leem o IP via `getHostString()`: com `forward-headers-strategy=framework` o WebFlux **consome** o `X-Forwarded-For` e troca o `remoteAddress` por um `InetSocketAddress` *unresolved* (`getAddress()` = null — `getHostAddress()` daria NPE/500). |
| auth-server | `server.forward-headers-strategy=framework` → `Secure` no `AUTHSESSION` + IP real (C19). |

## Voltar ao HTTP puro

O fluxo de dev em HTTP segue intacto — basta **não** usar o overlay:

```bash
docker compose up -d --build      # base + override (HTTP, portas internas republicadas)
```

Sem o overlay, `app.cookie.secure` é `false` (default) e os cookies saem sem `Secure`
(como antes), o que é o correto sobre HTTP.

## Para produção

- Trocar o cert do mkcert por **ACME (Let's Encrypt)** ou **corporativo**.
- Trocar `app.localhost`/`auth.localhost` por **domínios reais** (ex.: `app.exemplo.com`,
  `auth.exemplo.com`) — ver os placeholders comentados em `.env.example`.
- Garantir, no ingress real, o `X-Forwarded-For`/`X-Forwarded-Proto` sanitizados
  (mesma exigência que o `tls-proxy` já satisfaz aqui).
