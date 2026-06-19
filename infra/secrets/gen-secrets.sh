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
#
# DEV : rode UMA vez antes do `docker compose up`. Gera DEFAULTS DE DESENVOLVIMENTO.
# PROD: rode com cada segredo exportado no ambiente (valores fortes), ex.:
#         REDIS_PASSWORD=$(openssl rand -hex 32) OAUTH_CLIENT_SECRET=... infra/secrets/gen-secrets.sh
#       ou edite cada arquivo em ./secrets/ à mão. NUNCA versione ./secrets/ (gap 0.1/0.3).
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
# (host/porta compatíveis com um MailHog/Mailpit local). Em prod, exporte os 4 com valores
# reais do provedor SMTP escolhido antes de rodar este script.
SMTP_HOST="${SMTP_HOST:-localhost}"
SMTP_PORT="${SMTP_PORT:-1025}"
SMTP_USERNAME="${SMTP_USERNAME:-}"
SMTP_PASSWORD="${SMTP_PASSWORD:-}"

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
# URI completa do Mongo: o user-service resolve ${MONGODB_URI} via configtree.
write MONGODB_URI "mongodb://${MONGO_USER}:${MONGO_PASSWORD}@mongo-1:27017,mongo-2:27017,mongo-3:27017/user-db?replicaSet=rs0&authSource=admin"

# redis_exporter (multi-target): --redis.password-file espera um JSON {target: senha}, não a
# senha crua. Mapeia os 6 alvos do Prometheus (3 data nodes + 3 sentinels) à REDIS_PASSWORD.
write redis_exporter_json "{\"redis://redis-1:6379\":\"${REDIS_PASSWORD}\",\"redis://redis-2:6379\":\"${REDIS_PASSWORD}\",\"redis://redis-3:6379\":\"${REDIS_PASSWORD}\",\"redis://redis-sentinel-1:26379\":\"${REDIS_PASSWORD}\",\"redis://redis-sentinel-2:26379\":\"${REDIS_PASSWORD}\",\"redis://redis-sentinel-3:26379\":\"${REDIS_PASSWORD}\"}"

# Par de assinatura JWT (gap 0.1): reaproveita gen-keys.sh gerando direto no diretório de secrets.
bash "$ROOT/infra/jwk/gen-keys.sh" "$SECRETS_DIR" >/dev/null
mv -f "$SECRETS_DIR/app.key" "$SECRETS_DIR/jwk_private"
mv -f "$SECRETS_DIR/app.pub" "$SECRETS_DIR/jwk_public"
chmod 644 "$SECRETS_DIR/jwk_private"

echo "Secrets gerados em $SECRETS_DIR (DEFAULTS DE DEV — substitua em produção)."
