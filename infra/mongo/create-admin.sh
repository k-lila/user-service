#!/usr/bin/env bash
# Insere um usuário com role ADMIN direto na coleção `users` do MongoDB.
#
# Por que existe: bootstrap do primeiro ADMIN de um ambiente. O caminho normal de
# promoção (PATCH /v1/admin/users/{id}/roles, ADR-014) exige que já exista um ADMIN
# para chamá-lo — não resolve o problema do primeiro. Este script é um atalho de
# infra, fora da API, para destravar esse ovo-e-galinha.
#
# Hash de senha: BCrypt custo 10 via `htpasswd -B` (mesmo algoritmo do
# BCryptPasswordEncoder do Spring Security; prefixo $2y$ é aceito pelo BCrypt.checkpw
# usado internamente, equivalente a $2a$/$2b$ para fins de verificação).
#
# Conexão: a escrita PRECISA ir ao primário do replica set, e o primário é eleito — não é
# sempre o mongo-1. Por isso o mongosh recebe a seed-list no formato `rs0/host1,host2,host3`
# (descoberta de topologia + roteamento ao primário) em vez de conexão direta ao nó local;
# com conexão direta a um secundário o insert falha com NotWritablePrimary. O `exec` continua
# sendo no mongo-1 só para ter o binário do mongosh e a resolução DNS da rede Docker.
#
# Requisitos: htpasswd (apache2-utils), docker compose com o replica set no ar.
#
# Uso:
#   EMAIL=admin@example.com PASSWORD='SenhaForte123' infra/mongo/create-admin.sh "Nome Admin"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

MONGO_RS="${MONGO_RS:-rs0}"
MONGO_HOSTS="${MONGO_HOSTS:-mongo-1:27017,mongo-2:27017,mongo-3:27017}"

EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
NAME="${1:-Administrator}"

if [[ -z "$EMAIL" || -z "$PASSWORD" ]]; then
  echo "Uso: EMAIL=admin@example.com PASSWORD='SenhaForte123' $0 [\"Nome\"]" >&2
  exit 1
fi

if ! command -v htpasswd >/dev/null 2>&1; then
  echo "htpasswd não encontrado (pacote apache2-utils / httpd-tools)." >&2
  exit 1
fi

MONGO_PASSWORD_FILE="$ROOT/secrets/MONGO_PASSWORD"
if [[ ! -f "$MONGO_PASSWORD_FILE" ]]; then
  echo "Secret $MONGO_PASSWORD_FILE não encontrado — rode infra/secrets/gen-secrets.sh primeiro." >&2
  exit 1
fi
MONGO_PASSWORD="$(cat "$MONGO_PASSWORD_FILE")"

# O usuário root do Mongo NÃO é um Docker secret — o compose o lê do .env
# (MONGO_INITDB_ROOT_USERNAME: ${MONGO_USER}). Mesma fonte, mesmo idioma de leitura do
# gen-secrets.sh; sem default silencioso, senão um .env com usuário customizado falharia
# aqui como erro de autenticação sem dizer o motivo.
MONGO_USER="${MONGO_USER:-$(sed -n 's/^[[:space:]]*MONGO_USER=//p' "$ROOT/.env" 2>/dev/null | tail -1 | tr -d "\"'")}"
if [[ -z "$MONGO_USER" ]]; then
  echo "MONGO_USER não definido — exporte-o ou declare-o em $ROOT/.env (o compose lê de lá)." >&2
  exit 1
fi

PASSWORD_HASH="$(htpasswd -nbBC 10 _ "$PASSWORD" | cut -d: -f2)"

# Os valores chegam ao mongosh por ambiente (process.env), NUNCA interpolados no corpo do JS:
# um nome ou e-mail com aspas/barra quebraria o --eval e, no limite, injetaria JS no shell do
# Mongo. O heredoc é 'EOF' (quoted) justamente para não expandir nada aqui.
EVAL_JS=$(cat <<'EOF'
const email = process.env.ADMIN_EMAIL;
if (db.users.findOne({ email: email })) {
  print("ERRO: já existe um usuário com este e-mail.");
  quit(1);
}
db.users.insertOne({
  name: process.env.ADMIN_NAME,
  email: email,
  passwordHash: process.env.ADMIN_PASSWORD_HASH,
  registrationDate: new Date(),
  roles: ["USER", "ADMIN"],
  active: true,
  consentAcceptedAt: new Date(),
  termsVersion: "v1",
  tenantIds: null,
  emailVerified: true,
  emailVerifiedAt: new Date(),
});
print("ADMIN criado: " + email);
EOF
)

docker compose exec -T \
  -e ADMIN_NAME="$NAME" \
  -e ADMIN_EMAIL="$EMAIL" \
  -e ADMIN_PASSWORD_HASH="$PASSWORD_HASH" \
  mongo-1 mongosh \
  --quiet \
  --host "${MONGO_RS}/${MONGO_HOSTS}" \
  -u "$MONGO_USER" -p "$MONGO_PASSWORD" \
  --authenticationDatabase admin \
  "user-db" \
  --eval "$EVAL_JS"
