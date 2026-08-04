#!/usr/bin/env bash
# Gera os arquivos de Docker secret consumidos pelo docker-compose.yml (base secrets-native).
#
# Por que existe: tira os segredos do .env plano (gap 0.3 RELATORIOA). Cada segredo vira um
# arquivo em ./secrets/ (gitignorado), montado em /run/secrets/<NOME> nos containers e
# consumido por:
#   - serviços Spring  → spring.config.import=configtree:/run/secrets/ (nome do arquivo = placeholder)
#   - postgres / mongo → convenção nativa *_FILE da imagem oficial
#   - redis / sentinel → command/healthcheck leem o arquivo
#   - grafana          → GF_SECURITY_ADMIN_PASSWORD__FILE
#   - prometheus       → basic_auth.password_file
#   - cloudflared      → credentials-file no infra/cloudflared/config.yml (overlay de deploy)
#
# DEV : rode UMA vez antes do `docker compose up`. Gera DEFAULTS DE DESENVOLVIMENTO.
# PROD: rode com cada segredo exportado no ambiente (valores fortes), ex.:
#         REDIS_PASSWORD=$(openssl rand -hex 32) OAUTH_CLIENT_SECRET=... infra/secrets/gen-secrets.sh
#       ou edite cada arquivo em ./secrets/ à mão. NUNCA versione ./secrets/ (gap 0.1/0.3).
#
# ATENÇÃO — ESTE SCRIPT É DESTRUTIVO EM AMBIENTE VIVO. Ele não reconcilia nada: sobrescreve
# TODOS os arquivos de ./secrets/ incondicionalmente, e o que não vier exportado volta ao
# default de DEV. Duas consequências que não se desfazem:
#   1. senhas de produção não exportadas são substituídas pelos placeholders de dev;
#   2. o par JWK é REGERADO (openssl genpkey roda sempre, ver o fim do arquivo) — a chave de
#      assinatura muda, todo access/refresh token em circulação deixa de ser verificável e
#      todos os usuários logados caem.
# Por isso o roteiro do README § 2b termina em `down -v` + `up`: o estado é descartado junto.
# Para acrescentar UM secret novo a um deploy existente, NÃO re-rode este script — crie só o
# arquivo que falta (ex.: `printf '%s' false > secrets/SMTP_SSL_ENABLE && chmod 644 ...`).
#
# Resíduo consciente: o mongodb-exporter (imagem distroless, sem shell nem flag de arquivo)
# continua lendo MONGO_USER/MONGO_PASSWORD do ambiente (.env) — ver docs/SECURITY.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SECRETS_DIR="${SECRETS_DIR:-$ROOT/secrets}"
mkdir -p "$SECRETS_DIR"

# Defaults DE DEV — sobrescreva via variável de ambiente de mesmo nome para prod.
CONFIG_SERVER_PASSWORD="${CONFIG_SERVER_PASSWORD:-config-dev-secret}"
REDIS_PASSWORD="${REDIS_PASSWORD:-redis-dev-secret}"
OAUTH_CLIENT_SECRET="${OAUTH_CLIENT_SECRET:-oauth-dev-secret}"
INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-internal-dev-token}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres-dev-secret}"
MONGO_USER="${MONGO_USER:-root}"
MONGO_PASSWORD="${MONGO_PASSWORD:-mongo-dev-secret}"
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-admin}"
# notification-service (ADR-015): sem credenciais reais por default — só placeholders de dev
# (host/porta compatíveis com um MailHog/Mailpit local, auth/STARTTLS/SSL desligados). Em prod,
# exporte os 7 com valores reais do provedor SMTP escolhido antes de rodar este script.
# STARTTLS e SSL são as duas topologias possíveis, mutuamente exclusivas — escolha uma:
#   porta 587 (TLS oportunista): SMTP_AUTH=true SMTP_STARTTLS=true  SMTP_SSL_ENABLE=false
#   porta 465 (TLS implícito):   SMTP_AUTH=true SMTP_STARTTLS=false SMTP_SSL_ENABLE=true
SMTP_HOST="${SMTP_HOST:-localhost}"
SMTP_PORT="${SMTP_PORT:-1025}"
SMTP_USERNAME="${SMTP_USERNAME:-}"
SMTP_PASSWORD="${SMTP_PASSWORD:-}"
SMTP_AUTH="${SMTP_AUTH:-false}"
SMTP_STARTTLS="${SMTP_STARTTLS:-false}"
SMTP_SSL_ENABLE="${SMTP_SSL_ENABLE:-false}"
# Credenciais do named tunnel da Cloudflare (docker-compose.deploy.yml), modo LOCALLY-MANAGED.
# Não é um valor gerável: é o JSON emitido por `cloudflared tunnel create` em
# ~/.cloudflared/<UUID>.json. Aponte esta variável para esse caminho e o script COPIA o arquivo
# para ./secrets/. DELIBERADAMENTE sem default de dev — vazio é o comportamento correto: o
# cloudflared recusa a subida com credentials-file vazio (fail-fast), em vez de subir um túnel que
# não roteia para lugar nenhum. Só o overlay de deploy consome; a base e o dev ignoram.
CLOUDFLARE_TUNNEL_CREDENTIALS="${CLOUDFLARE_TUNNEL_CREDENTIALS:-}"

# STARTTLS e SSL são topologias de conexão, não níveis de rigor — ligar as duas é incoerente e
# o Jakarta Mail não avisa: tentaria STARTTLS sobre um socket que já está em TLS. Falha aqui,
# na geração, em vez de virar um envio quebrado só descoberto quando o e-mail não chega.
if [ "$SMTP_STARTTLS" = "true" ] && [ "$SMTP_SSL_ENABLE" = "true" ]; then
  echo "erro: SMTP_STARTTLS e SMTP_SSL_ENABLE são mutuamente exclusivos — escolha um:" >&2
  echo "      porta 587 → SMTP_STARTTLS=true  SMTP_SSL_ENABLE=false" >&2
  echo "      porta 465 → SMTP_STARTTLS=false SMTP_SSL_ENABLE=true" >&2
  exit 1
fi

# MONGO_USER tem DUAS fontes de verdade: aqui (assado dentro do MONGODB_URI, que o user-service
# lê via configtree) e o .env (que o compose usa em MONGO_INITDB_ROOT_USERNAME e no
# mongodb-exporter). Divergirem não quebra o `up` — quebra a autenticação em runtime, com o
# user-service sem conseguir falar com o Mongo. Aviso, e não erro: pode ser intencional durante
# uma troca de credencial.
if [ -f "$ROOT/.env" ]; then
  env_mongo_user="$(sed -n 's/^[[:space:]]*MONGO_USER=//p' "$ROOT/.env" | tail -1 | tr -d "\"'")"
  if [ -n "$env_mongo_user" ] && [ "$env_mongo_user" != "$MONGO_USER" ]; then
    echo "aviso: MONGO_USER diverge — .env='$env_mongo_user' vs este script='$MONGO_USER'." >&2
    echo "       O MONGODB_URI vai usar '$MONGO_USER'; o compose cria o root como" >&2
    echo "       '$env_mongo_user'. Alinhe os dois ou a autenticação falha em runtime." >&2
  fi
fi

# printf '%s' (sem \n final): o configtree do Spring e o `cat` do redis usam o conteúdo
# literal — um newline final entraria na senha e quebraria a autenticação.
#
# chmod 644 (e não 600): no Compose não-Swarm os secrets `file:` são bind-mountados
# PRESERVANDO as permissões do host (as chaves uid/gid/mode do long-syntax só valem em
# Swarm). Os consumidores rodam como usuários NÃO-root — config-server/auth-server/gateway/
# user-service como `appuser` (UID 999), mongo re-exec como `mongodb`, grafana UID 472 — e
# não conseguiriam ler um arquivo 600 do dono UID do host. 644 os torna legíveis. Tradeoff:
# legível por outros usuários do host; aceito em ./secrets/ (gitignorado, host single-tenant).
write() { printf '%s' "$2" > "$SECRETS_DIR/$1"; chmod 644 "$SECRETS_DIR/$1"; }

# Percent-encoding para o userinfo do MONGODB_URI. Sem isto, uma senha forte contendo @ : / ? #
# produz uma URI SILENCIOSAMENTE quebrada — 'p@ss/w0rd' faz o driver ler a senha como 'p' e o
# host como 'ss'. Não falha no `up`: falha em runtime, no primeiro acesso ao Mongo.
# LC_ALL=C para iterar BYTES e não caracteres — senha multibyte tem de ser encodada byte a byte.
urlencode() {
  local LC_ALL=C s="$1" out="" i c
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      # Unreserved da RFC 3986: tudo mais é encodado, inclusive os sub-delims que o
      # connection string do Mongo interpreta.
      [A-Za-z0-9._~-]) out+="$c" ;;
      *) printf -v c '%%%02X' "$(( $(printf '%d' "'$c") & 0xFF ))"; out+="$c" ;;
    esac
  done
  printf '%s' "$out"
}

# Escape de string JSON para o redis_exporter_json. Uma senha com aspas ou barra invertida gera
# JSON inválido e o redis-exporter perde a autenticação (só as métricas quebram, não a app).
# A barra invertida vem primeiro, senão o escape da aspa seria re-escapado.
json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

write CONFIG_SERVER_PASSWORD "$CONFIG_SERVER_PASSWORD"
write REDIS_PASSWORD         "$REDIS_PASSWORD"
write OAUTH_CLIENT_SECRET    "$OAUTH_CLIENT_SECRET"
write INTERNAL_API_TOKEN     "$INTERNAL_API_TOKEN"
write POSTGRES_PASSWORD      "$POSTGRES_PASSWORD"
write MONGO_PASSWORD         "$MONGO_PASSWORD"
write GRAFANA_ADMIN_PASSWORD "$GRAFANA_ADMIN_PASSWORD"
write SMTP_HOST              "$SMTP_HOST"
write SMTP_PORT              "$SMTP_PORT"
write SMTP_USERNAME          "$SMTP_USERNAME"
write SMTP_PASSWORD          "$SMTP_PASSWORD"
write SMTP_AUTH              "$SMTP_AUTH"
write SMTP_STARTTLS          "$SMTP_STARTTLS"
write SMTP_SSL_ENABLE        "$SMTP_SSL_ENABLE"
# Credenciais do túnel: COPIADAS de ~/.cloudflared/<UUID>.json, não geradas. Ausente = arquivo
# vazio (fail-fast do cloudflared); caminho informado mas inexistente = erro aqui, porque nesse
# caso o operador quis configurar o túnel e errou o caminho — falhar em silêncio só adiaria o
# diagnóstico para a subida.
if [ -n "$CLOUDFLARE_TUNNEL_CREDENTIALS" ]; then
  [ -f "$CLOUDFLARE_TUNNEL_CREDENTIALS" ] || {
    echo "erro: CLOUDFLARE_TUNNEL_CREDENTIALS aponta para arquivo inexistente: $CLOUDFLARE_TUNNEL_CREDENTIALS" >&2
    exit 1
  }
  cp "$CLOUDFLARE_TUNNEL_CREDENTIALS" "$SECRETS_DIR/CLOUDFLARE_TUNNEL_CREDENTIALS"
  chmod 644 "$SECRETS_DIR/CLOUDFLARE_TUNNEL_CREDENTIALS"
else
  write CLOUDFLARE_TUNNEL_CREDENTIALS ""
fi
# URI completa do Mongo: o user-service resolve ${MONGODB_URI} via configtree. Usuário e senha
# passam por urlencode — ver o comentário da função.
write MONGODB_URI "mongodb://$(urlencode "$MONGO_USER"):$(urlencode "$MONGO_PASSWORD")@mongo-1:27017,mongo-2:27017,mongo-3:27017/user-db?replicaSet=rs0&authSource=admin"

# redis_exporter (multi-target): --redis.password-file espera um JSON {target: senha}, não a
# senha crua. Mapeia os 6 alvos do Prometheus (3 data nodes + 3 sentinels) à REDIS_PASSWORD.
redis_pw_json="$(json_escape "$REDIS_PASSWORD")"
write redis_exporter_json "{\"redis://redis-1:6379\":\"${redis_pw_json}\",\"redis://redis-2:6379\":\"${redis_pw_json}\",\"redis://redis-3:6379\":\"${redis_pw_json}\",\"redis://redis-sentinel-1:26379\":\"${redis_pw_json}\",\"redis://redis-sentinel-2:26379\":\"${redis_pw_json}\",\"redis://redis-sentinel-3:26379\":\"${redis_pw_json}\"}"

# Par de assinatura JWT (gap 0.1): reaproveita gen-keys.sh gerando direto no diretório de secrets.
bash "$ROOT/infra/jwk/gen-keys.sh" "$SECRETS_DIR" >/dev/null
mv -f "$SECRETS_DIR/app.key" "$SECRETS_DIR/jwk_private"
mv -f "$SECRETS_DIR/app.pub" "$SECRETS_DIR/jwk_public"
chmod 644 "$SECRETS_DIR/jwk_private"

echo "Secrets gerados em $SECRETS_DIR (DEFAULTS DE DEV — substitua em produção)."
