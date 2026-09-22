#!/usr/bin/env bash
# Gera tráfego realista pelo domínio público: cadastro → login BFF → buscas → erros.
# Uso: BASE_URL=https://seu-dominio N=20 ./traffic.sh
set -u
BASE_URL="${BASE_URL:?defina BASE_URL}"
N="${N:-10}"
PASS="SeedPass123"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

req() { # req <jar> <método> <path> [args curl...] → imprime status
	local jar="$1" m="$2" p="$3"; shift 3
	curl -s -o /dev/null -w '%{http_code}' -b "$jar" -c "$jar" -X "$m" "$BASE_URL$p" "$@"
}

register() { # register <email> → status
	curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/v1/users/register" \
		-H 'Content-Type: application/json' \
		-d "{\"name\":\"Seed User\",\"email\":\"$1\",\"password\":\"$PASS\",\"termsAccepted\":true}"
}

login() { # login <jar> <email> <senha> → status final de GET /v1/users/me
	local jar="$1" form csrf
	# 1) inicia o authorization_code no gateway; -L segue até o formulário do IdP
	form="$(curl -s -L -b "$jar" -c "$jar" "$BASE_URL/oauth2/authorization/gateway-client")"
	csrf="$(printf '%s' "$form" | grep -o '<input[^>]*_csrf[^>]*>' | grep -o 'value="[^"]*"' | head -1 | cut -d'"' -f2)"
	[ -z "$csrf" ] && { echo "sem-csrf"; return; }
	# 2) POST do formulário; -L segue /oauth2/authorize → callback do gateway → SPA
	curl -s -L -o /dev/null -b "$jar" -c "$jar" "$BASE_URL/login" \
		--data-urlencode "username=$2" --data-urlencode "password=$3" --data-urlencode "_csrf=$csrf"
	req "$jar" GET /v1/users/me
}

xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$1" | tail -1; }

for i in $(seq 1 "$N"); do
	email="seed-$(date +%s)-$i@example.com"
	jar="$WORK/$i.jar"; anon="$WORK/anon.jar"; : > "$anon"
	out="[$i] register=$(register "$email")"; sleep 0.6

	out+=" login=$(login "$jar" "$email" "$PASS")"
	for _ in 1 2 3; do out+=" me=$(req "$jar" GET /v1/users/me)"; done

	# --- erros ---
	out+=" | 401:$(req "$anon" GET /v1/users/me)"                          # sem sessão
	out+=" 404:$(req "$jar" GET /v1/users/nao-existe)"                     # rota inexistente
	out+=" 405:$(req "$jar" GET /v1/users/remove/me)"                      # método errado
	out+=" 403:$(req "$jar" PUT /v1/users -H 'Content-Type: application/json' -d '{}')" # sem X-XSRF-TOKEN
	out+=" 400put:$(req "$jar" PUT /v1/users -H 'Content-Type: application/json' \
		-H "X-XSRF-TOKEN: $(xsrf "$jar")" -d '{"name":"","email":"invalido"}')"   # validação
	out+=" 400reg:$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/v1/users/register" \
		-H 'Content-Type: application/json' -d '{"name":"x"}')"              # corpo inválido
	# login com conta inexistente (e-mail aleatório: não acumula lockout numa conta real)
	out+=" badlogin:$(login "$WORK/bad.jar" "nobody-$RANDOM@example.com" "wrong")"; rm -f "$WORK/bad.jar"

	echo "$out"
	sleep 1
done
