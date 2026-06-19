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
# Requisitos: htpasswd (apache2-utils), docker compose com o serviço mongo-1 no ar.
#
# Uso:
#   EMAIL=admin@example.com PASSWORD='SenhaForte123' infra/mongo/create-admin.sh "Nome Admin"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

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

MONGO_USER_FILE="$ROOT/secrets/MONGO_USER"
MONGO_PASSWORD_FILE="$ROOT/secrets/MONGO_PASSWORD"
if [[ ! -f "$MONGO_PASSWORD_FILE" ]]; then
  echo "Secret $MONGO_PASSWORD_FILE não encontrado — rode infra/secrets/gen-secrets.sh primeiro." >&2
  exit 1
fi
MONGO_USER="$(cat "$MONGO_USER_FILE" 2>/dev/null || echo "${MONGO_USER:-root}")"
MONGO_PASSWORD="$(cat "$MONGO_PASSWORD_FILE")"

PASSWORD_HASH="$(htpasswd -nbBC 10 _ "$PASSWORD" | cut -d: -f2)"

EVAL_JS=$(cat <<EOF
const email = "${EMAIL}";
if (db.users.findOne({ email: email })) {
  print("ERRO: já existe um usuário com este e-mail.");
  quit(1);
}
db.users.insertOne({
  name: "${NAME}",
  email: email,
  passwordHash: "${PASSWORD_HASH}",
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

docker compose -f "$ROOT/docker-compose.yml" exec -T mongo-1 mongosh \
  --quiet \
  -u "$MONGO_USER" -p "$MONGO_PASSWORD" \
  --authenticationDatabase admin \
  "user-db" \
  --eval "$EVAL_JS"
