#!/usr/bin/env bash
# Gera o par de chaves RSA usado pelo authorization-server para assinar os JWT.
#
# Por que existe: a chave de assinatura NÃO mora mais no repositório (gap 0.1 do
# RELATORIOA — chave privada versionada = forja de identidade). Cada ambiente gera
# o seu par fora do versionamento:
#   - dev / CI : gera em authorization-server/src/main/resources/keys (gitignorado)
#   - prod     : gera num diretório de secrets e monta via JWK_PRIVATE_KEY/JWK_PUBLIC_KEY
#
# Formato exigido por JWKConfig.java (RsaKeyConverters):
#   - privada: PKCS#8 PEM  (-----BEGIN PRIVATE KEY-----)
#   - pública: X.509  PEM  (-----BEGIN PUBLIC KEY-----)
#
# Uso:
#   infra/jwk/gen-keys.sh [DIR_SAIDA]
# DIR_SAIDA default: authorization-server/src/main/resources/keys
set -euo pipefail

OUT_DIR="${1:-authorization-server/src/main/resources/keys}"
KEY_FILE="${OUT_DIR}/app.key"
PUB_FILE="${OUT_DIR}/app.pub"
BITS="${JWK_RSA_BITS:-2048}"

mkdir -p "$OUT_DIR"

openssl genpkey -algorithm RSA -pkeyopt "rsa_keygen_bits:${BITS}" -out "$KEY_FILE"
openssl pkey -in "$KEY_FILE" -pubout -out "$PUB_FILE"

chmod 600 "$KEY_FILE"
chmod 644 "$PUB_FILE"

echo "Par RSA (${BITS} bits) gerado em ${OUT_DIR}: app.key, app.pub"
