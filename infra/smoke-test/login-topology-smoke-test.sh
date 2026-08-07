#!/usr/bin/env bash
# Smoke-test automatizado da topologia de login sob hostname único (ADR-023).
#
# POR QUE EXISTE. O ADR-019 documentou quatro defeitos na cadeia
#   nginx (interface) → gateway → authorization-server
# — Elo 2 (`trusted-proxies` sem default no SCG 5.0.0), Elo 3 (`/login` e `/default-ui.css` sem
# `location` no nginx.conf), Elo 6 (ForwardedHeaderTransformer neutralizando o Elo 2 via XFF) e
# BUG-001 (POST /login → 403 pelo CSRF do gateway) — e TODOS foram descobertos ao vivo, em
# produção. Nenhuma camada de teste exercita essa cadeia: unitários e @WebMvcTest batem direto no
# controller, os Testcontainers nunca sobem o container `interface`, o job compose-validate do CI
# só valida sintaxe YAML, e o dev local vai direto a localhost:8082 — sem nginx e sem o CSRF do
# gateway no caminho. Este script é a barreira automática que faltava, pré-merge.
#
# POR QUE bash+curl E NÃO UM BROWSER HEADLESS. Nenhum dos bugs históricos é de comportamento JS
# renderizado: são status code, header Location e Content-Type. Playwright/Cypress seria mais caro
# de manter e mais frágil sem cobrir nada que o curl não cubra (ADR-023 § Alternativas).
#
# AS CINCO ASSERÇÕES (ADR-023 § Mecanismo 4):
#   (e) a base do compose não publica 8081/5173                      → G10
#   (a) GET  /login devolve o form do IdP, não o index.html do SPA   → Elo 3
#   (b) GET  /default-ui.css é text/css                              → Elo 3
#   (c) POST /login não é barrado pelo CSRF do gateway               → BUG-001
#   (d) Location de /oauth2/authorize traz host/proto públicos       → Elo 2 + Elo 6
#
# USO:
#   bash infra/smoke-test/login-topology-smoke-test.sh
#   SMOKE_KEEP_STACK=1 bash infra/smoke-test/login-topology-smoke-test.sh   # não derruba no fim
#
# SMOKE_KEEP_STACK=1 mantém a stack NO AR para inspeção depois do teste. Ele NÃO preserva dado: a
# purga de estado acontece ANTES da subida, e é obrigatória (ver o comentário em compose_up).
#
# ⚠️  DESTRUTIVO. Sobe a stack com o MESMO nome de projeto Compose do deploy (o nome sai do
# diretório) e roda `down -v` ANTES e DEPOIS — o que APAGA os volumes de Mongo e Postgres. O `down -v`
# de antes não é zelo excessivo: sem ele o `gateway-client` herdado de outro PUBLIC_ORIGIN faz a
# asserção (d) reprovar por estado sujo (ver compose_up). Não dá para
# isolar por `-p nome-diferente`: `networks.default.name: user-service-net` fixa o nome da rede, e
# dois projetos na mesma rede colidiriam nos aliases de container.
# Por isso o script RECUSA rodar se já houver container do projeto no ar (ver
# assert_no_running_stack) — instrução em comentário é exatamente o antipadrão que o ADR-023
# rejeita. Derrube a stack você mesmo antes, para que a destruição seja um ato explícito.
#
# MANUTENÇÃO. Quem editar login-interface/nginx.conf, gateway/.../GatewayRouter.java ou os
# SecurityConfig do gateway/authorization-server tem de revisitar as asserções daqui — é a
# consequência negativa que o próprio ADR-023 registra.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Origem pública fictícia mas COERENTE entre si: o serviço assert-env do overlay de deploy deriva
# o hostname de PUBLIC_ORIGIN e falha se divergir de PUBLIC_HOST. O default de PUBLIC_ORIGIN sai
# de PUBLIC_HOST justamente para os dois nunca saírem de sincronia por descuido.
export PUBLIC_HOST="${PUBLIC_HOST:-smoke-test.local}"
export PUBLIC_ORIGIN="${PUBLIC_ORIGIN:-https://${PUBLIC_HOST}}"
# O cloudflared não roda aqui (sem DNS/túnel real), mas `${TUNNEL_ID:?}` é interpolado na LEITURA
# do docker-compose.deploy.yml, não na subida do serviço — sem um valor, o compose nem parseia.
export TUNNEL_ID="${TUNNEL_ID:-00000000-0000-0000-0000-000000000000}"

# Rede fixada por `networks.default.name` no docker-compose.yml (sem prefixo de projeto).
NET="user-service-net"
CURL_IMAGE="${SMOKE_CURL_IMAGE:-curlimages/curl:8.11.1}"
BASE="http://interface"
# code_challenge fixo (o exemplo da RFC 7636). Nunca é verificado — o fluxo morre no redirect para
# o login —, mas TEM de estar presente: com requireProofKey(true) o provider do SAS valida o PKCE
# ANTES de checar se o principal está autenticado, e sem ele a resposta é erro, não o 302.
CODE_CHALLENGE="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

FAILURES=0
STACK_UP=0

log()  { printf '\n=== %s\n' "$*"; }
pass() { printf '  [OK]    %s\n' "$*"; }
fail() { printf '  [FALHA] %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
die()  { printf '\n[ERRO] %s\n' "$*" >&2; exit 1; }

compose() {
	docker compose -f "$ROOT/docker-compose.yml" -f "$ROOT/docker-compose.deploy.yml" "$@"
}

# curl a partir de um container efêmero na rede interna do compose — mesmo padrão de "container
# minimalista de infraestrutura" do assert-env, e evita publicar porta nova no host.
#
# Host + X-Forwarded-Proto em TODO request: é o que o cloudflared entrega ao nginx em produção.
# Sem eles o `map $http_x_forwarded_proto` do nginx.conf cai em $scheme (http) e o Host vira
# "interface" — a asserção (d) passaria a testar outra coisa.
#
# `|| true`: falha de conexão não pode matar o script pelo `set -e`; quem julga é a asserção.
dcurl() {
	docker run --rm --network "$NET" "$CURL_IMAGE" \
		-s --max-time 20 \
		-H "Host: ${PUBLIC_HOST}" \
		-H "X-Forwarded-Proto: https" \
		"$@" || true
}

# Último valor de um header, sem CR e sem o nome. `|| true` porque grep sem match retorna 1 e
# mataria o script dentro de `var=$(...)` sob `set -e`.
header_value() {
	printf '%s' "$1" | tr -d '\r' | grep -i "^$2:" | tail -1 | sed 's/^[^:]*: *//' || true
}

http_code() {
	printf '%s' "$1" | sed -n 's/^HTTP_CODE=//p' | tail -1
}

# ──────────────────────────────────────────────────────────────────────────────
# Pré-requisitos
# ──────────────────────────────────────────────────────────────────────────────
require_cmds() {
	command -v docker >/dev/null || die "docker não encontrado no PATH."
	docker compose version >/dev/null 2>&1 || die "docker compose (v2) não encontrado."
	# jq é o que permite checar a AUSÊNCIA da chave `ports` na asserção (e) — ver o comentário lá.
	command -v jq >/dev/null || die "jq não encontrado no PATH (necessário para a asserção (e))."
}

# GUARDA FAIL-CLOSED. O projeto Compose é o mesmo do deploy (nome derivado do diretório), então
# rodar este script com a stack no ar significaria: recriar os containers com um PUBLIC_ORIGIN
# fictício — derrubando o deploy real — e, no fim, `down -v` nos volumes de Mongo e Postgres.
# Nenhum dos dois é reversível. A saída é o operador derrubar a stack explicitamente antes.
# Em CI nunca há stack no ar, então a guarda é inerte lá.
assert_no_running_stack() {
	local running
	running="$(compose ps -q 2>/dev/null | grep -c . || true)"
	if [ "$running" != "0" ] && [ "${SMOKE_FORCE:-0}" != "1" ]; then
		die "$(
			cat <<-EOF
				há $running container(es) do projeto Compose no ar.
				       Este script recria a stack com PUBLIC_ORIGIN=${PUBLIC_ORIGIN} e roda 'down -v' no fim,
				       APAGANDO os volumes de Mongo e Postgres — e derrubando um deploy real, se for o caso.
				       Derrube a stack você mesmo antes de rodar:
				         docker compose -f docker-compose.yml -f docker-compose.deploy.yml down
				       (SMOKE_FORCE=1 ignora esta guarda — só faça isso sabendo o que vai perder.)
			EOF
		)"
	fi
}

prepare_env() {
	if [ ! -f "$ROOT/.env" ]; then
		log "Criando .env a partir de .env.example"
		cp "$ROOT/.env.example" "$ROOT/.env"
	fi
	# NUNCA re-rodar gen-secrets.sh sobre um ./secrets/ existente: ele sobrescreve tudo
	# incondicionalmente e REGERA o par JWK, invalidando todo token em circulação (ver o
	# cabeçalho daquele script).
	if [ ! -d "$ROOT/secrets" ]; then
		log "Gerando ./secrets/ (defaults de DEV)"
		bash "$ROOT/infra/secrets/gen-secrets.sh"
	fi
}

# ──────────────────────────────────────────────────────────────────────────────
# (e) A base do compose não publica 8081/5173 — G10 (ADR-019)
#
# Checagem ESTÁTICA: roda antes de subir qualquer coisa, como guarda rápida.
# A invariante do G10 é "gateway e interface não têm `ports:` na base" — e não "a porta 8081 não
# aparece". Um `9999:8081` passaria por um grep de número e continuaria publicando o gateway no
# host, que é exatamente o que o G10 proíbe. Daí a checagem pela ausência da chave, via jq.
# ──────────────────────────────────────────────────────────────────────────────
assert_base_compose_ports() {
	log "(e) Base do compose não publica gateway/interface (G10)"

	local cfg
	cfg="$(docker compose -f "$ROOT/docker-compose.yml" config --format json)" ||
		die "docker compose config falhou na topologia base."

	local svc n
	for svc in gateway interface; do
		n="$(printf '%s' "$cfg" | jq -r --arg s "$svc" '.services[$s].ports // [] | length')"
		if [ "$n" = "0" ]; then
			pass "serviço '$svc' sem ports: na base prod-safe"
		else
			fail "serviço '$svc' publica $n porta(s) na base — G10 regrediu; ports: pertence ao docker-compose.override.yml"
		fi
	done
}

# ──────────────────────────────────────────────────────────────────────────────
# Subida da topologia
# ──────────────────────────────────────────────────────────────────────────────
compose_up() {
	log "Subindo a topologia (base + overlay de deploy, sem cloudflared)"
	printf '  PUBLIC_ORIGIN=%s\n  PUBLIC_HOST=%s\n' "$PUBLIC_ORIGIN" "$PUBLIC_HOST"

	# PURGA DE ESTADO ANTES DE SUBIR — o teste precisa ser hermético, e aqui isso não é preferência.
	# O seed do `gateway-client` é IDEMPOTENTE E NÃO RECONCILIA (OAuth2ClientConfig): num volume de
	# Postgres herdado de outro PUBLIC_ORIGIN, o client mantém os `redirect_uris` antigos, o
	# `redirect_uri` da asserção (d) fica não-registrado e o SAS responde 400 SEM Location — que é o
	# comportamento correto dele, mas faz a (d) reprovar por sujeira de estado, não por regressão.
	# Em CI o volume nunca existe e esta linha é inócua; localmente ela é o que torna o resultado
	# reprodutível. Roda também sob SMOKE_KEEP_STACK=1: aquela variável mantém a stack NO AR para
	# inspeção depois, ela não preserva dado.
	STACK_UP=1
	log "Purgando estado anterior (down -v) — o seed do gateway-client não reconcilia redirect_uris"
	compose down -v --remove-orphans

	# Listar os alvos explicitamente é o que exclui o cloudflared: o Compose sobe só eles e suas
	# dependências (auth-server, user-service, discovery×2, config-lb+config-server×2, mongo×3,
	# postgres, redis×3, sentinels×3 e o assert-env). Ficam de fora zipkin, prometheus, grafana,
	# os exporters e o notification-service — nenhuma asserção os toca.
	compose up -d --build --wait --wait-timeout 900 interface gateway ||
		die "a stack não subiu (veja o diagnóstico abaixo)."
}

# As rotas do gateway são lb://authorization-server, resolvidas via Eureka: existe uma janela de
# ~30-60s após o gateway ficar healthy em que /login ainda responde 503. O --wait cobre só os
# healthchecks — sem esta espera ativa o teste é flaky por construção (ADR-023 § Consequências).
# Intervalo de 5s mantém folga no tier MED por-IP (5 req/s, cap 10) da rota auth-login.
wait_for_login() {
	log "Aguardando /login responder 200 pela cadeia nginx → gateway → auth-server"
	local deadline=$((SECONDS + 300)) code
	while :; do
		code="$(dcurl -o /dev/null -w '%{http_code}' "$BASE/login")"
		if [ "$code" = "200" ]; then
			printf '  pronto (%ss)\n' "$SECONDS"
			return 0
		fi
		[ "$SECONDS" -lt "$deadline" ] || die "/login não respondeu 200 em 300s (último código: $code)."
		sleep 5
	done
}

# ──────────────────────────────────────────────────────────────────────────────
# (a) GET /login devolve o form do IdP, não o index.html do SPA — Elo 3
#
# Asserção positiva E negativa. O nginx.conf precisa do `location /login`; sem ele a request cai
# no try_files do SPA, que renderiza <Login/> de novo e cria o loop do Elo 3. A AUSÊNCIA do
# marcador do SPA é o sinal direto dessa regressão — só checar a presença do form não bastaria se
# um dia o SPA passasse a servir algo parecido.
# ──────────────────────────────────────────────────────────────────────────────
assert_login_form() {
	log "(a) GET /login devolve o formulário do IdP (Elo 3)"

	local body
	body="$(dcurl "$BASE/login")"

	local marker missing=0
	# Marcadores do form gerado por formLogin(Customizer.withDefaults()) no auth-server.
	for marker in 'name="username"' 'name="password"' 'name="_csrf"'; do
		printf '%s' "$body" | grep -q -- "$marker" || { fail "corpo de /login sem o marcador $marker"; missing=1; }
	done
	if [ "$missing" = "0" ]; then
		pass "corpo traz o formulário de login do authorization-server"
	fi

	# <div id="root"> é a âncora do React em login-interface/index.html.
	if printf '%s' "$body" | grep -q 'id="root"'; then
		fail "/login devolveu o index.html do SPA — o location /login do nginx.conf sumiu (Elo 3)"
	else
		pass "corpo não é o index.html do SPA"
	fi
}

# ──────────────────────────────────────────────────────────────────────────────
# (b) GET /default-ui.css é text/css — Elo 3
#
# Sem o `location = /default-ui.css` no nginx, o CSS do formulário do IdP cai no try_files e volta
# como text/html; com nosniff, o browser recusa e o formulário fica sem estilo. GET e não HEAD:
# é o que o browser faz, e evita depender do tratamento de HEAD pelo DefaultResourcesFilter.
# ──────────────────────────────────────────────────────────────────────────────
assert_default_ui_css() {
	log "(b) GET /default-ui.css é servido como text/css (Elo 3)"

	local out code ctype
	out="$(dcurl -o /dev/null -D - -w '\nHTTP_CODE=%{http_code}\n' "$BASE/default-ui.css")"
	code="$(http_code "$out")"
	ctype="$(header_value "$out" content-type)"

	if [ "$code" = "200" ]; then
		pass "status 200"
	else
		fail "status $code (esperado 200)"
	fi

	case "$ctype" in
	*text/css*) pass "Content-Type: $ctype" ;;
	*) fail "Content-Type: '${ctype:-<ausente>}' (esperado text/css) — o CSS caiu no try_files do SPA (Elo 3)" ;;
	esac
}

# ──────────────────────────────────────────────────────────────────────────────
# (c) POST /login não é barrado pelo CSRF do gateway — BUG-001
#
# Um POST sem _csrf válido leva 403 do CSRF do PRÓPRIO auth-server, indistinguível do bug. O teste
# só tem valor com o token real, então é um fluxo de dois passos.
#
# COOKIE MONTADO À MÃO, e não -c/-b: o overlay de deploy liga APP_COOKIE_SECURE=true, o AUTHSESSION
# sai com flag Secure e o cookie engine do curl se recusa a reenviá-lo sobre HTTP — não há flag que
# contorne. Montar o header evita ter de relaxar a flag num quarto arquivo de compose e mantém a
# topologia IDÊNTICA à de produção.
#
# Headers e corpo na MESMA invocação: dois requests separados criariam duas sessões, e o _csrf de
# uma não casaria com o AUTHSESSION da outra.
# ──────────────────────────────────────────────────────────────────────────────
assert_post_login_not_blocked_by_csrf() {
	log "(c) POST /login atravessa o CSRF do gateway (BUG-001)"

	local resp authsession csrf
	resp="$(dcurl -D - "$BASE/login")"
	authsession="$(printf '%s' "$resp" | tr -d '\r' | sed -n 's/^[Ss]et-[Cc]ookie: *AUTHSESSION=\([^;]*\).*/\1/p' | tail -1)"
	# grep do input inteiro e só depois do value: robusto à ordem dos atributos.
	csrf="$(printf '%s' "$resp" | grep -o '<input[^>]*_csrf[^>]*>' | grep -o 'value="[^"]*"' | head -1 | cut -d'"' -f2 || true)"

	if [ -z "$authsession" ] || [ -z "$csrf" ]; then
		fail "não foi possível extrair AUTHSESSION/_csrf do GET /login (cookie='${authsession:-vazio}', csrf='${csrf:-vazio}') — asserção (c) não pôde ser exercida"
		return
	fi

	local out code loc
	out="$(dcurl -o /dev/null -D - -w '\nHTTP_CODE=%{http_code}\n' \
		-H "Cookie: AUTHSESSION=${authsession}" \
		--data-urlencode "username=smoke-test@example.invalid" \
		--data-urlencode "password=senha-invalida-do-smoke-test" \
		--data-urlencode "_csrf=${csrf}" \
		"$BASE/login")"
	code="$(http_code "$out")"
	loc="$(header_value "$out" location)"

	if [ "$code" = "403" ]; then
		fail "POST /login → 403: o CSRF do gateway voltou a barrar a request antes do auth-server (BUG-001)"
		return
	fi
	pass "POST /login não retornou 403"

	# 302 para /login?error é o SimpleUrlAuthenticationFailureHandler do auth-server respondendo a
	# credencial inválida — prova de que a request chegou ao formLogin em vez de morrer no
	# ServerCsrfTokenRequestAttributeHandler do gateway. O Location absoluto na origem pública é,
	# de quebra, uma segunda evidência dos X-Forwarded-* corretos.
	if [ "$code" = "302" ] && [ "$loc" = "${PUBLIC_ORIGIN}/login?error" ]; then
		pass "302 → ${loc} (credencial inválida tratada pelo auth-server)"
	else
		fail "esperado 302 → ${PUBLIC_ORIGIN}/login?error; recebido $code → '${loc:-<sem Location>}'"
	fi
}

# ──────────────────────────────────────────────────────────────────────────────
# (d) Location de /oauth2/authorize traz host/proto públicos — Elo 2 + Elo 6
#
# X-Forwarded-For: 203.0.113.9 NÃO É DECORATIVO — é o probe do Elo 6. O nginx.conf suprime o XFF
# em todos os blocos; se essa supressão regredir, o ForwardedHeaderTransformer do gateway reescreve
# remoteAddress para 203.0.113.9, `trusted-proxies` (10./172.16-31./192.168.) deixa de casar, o
# XForwardedHeadersFilter não emite X-Forwarded-Host/Proto ao auth-server e o Location volta ao
# hostname interno do container. SEM ESTE HEADER O TESTE NÃO PEGA O ELO 6: o IP do próprio
# container de curl é 172.x, dentro da faixa confiável, e a regressão passaria despercebida.
#
# IGUALDADE EXATA no Location, e não prefixo: erros não-fatais do endpoint de autorização
# redirecionam para o próprio redirect_uri (${PUBLIC_ORIGIN}/login/oauth2/code/...?error=...), que
# também começa com a origem pública sem provar nada sobre os X-Forwarded-*.
# ──────────────────────────────────────────────────────────────────────────────
assert_authorize_location() {
	log "(d) Location de /oauth2/authorize traz host/proto públicos (Elo 2 + Elo 6)"

	local redirect_enc url out code loc
	redirect_enc="$(printf '%s' "${PUBLIC_ORIGIN}/login/oauth2/code/gateway-client" | sed -e 's|:|%3A|g' -e 's|/|%2F|g')"
	url="${BASE}/oauth2/authorize?response_type=code&client_id=gateway-client&redirect_uri=${redirect_enc}&scope=openid&code_challenge=${CODE_CHALLENGE}&code_challenge_method=S256"

	out="$(dcurl -o /dev/null -D - -w '\nHTTP_CODE=%{http_code}\n' -H "X-Forwarded-For: 203.0.113.9" "$url")"
	code="$(http_code "$out")"
	loc="$(header_value "$out" location)"

	# 400 SEM Location tem causa distinta de "Location com host errado", e confundir as duas manda
	# quem for depurar caçar uma regressão de Elo 6 que não existe: o SAS recusa redirecionar para
	# `redirect_uri` não registrado e responde 400 direto. Na prática significa Postgres semeado com
	# OUTRO PUBLIC_ORIGIN — o que a purga de `compose_up` previne, mas a mensagem fica de rede de
	# segurança para quem rodar o `up` à mão.
	if [ "$code" = "400" ] && [ -z "$loc" ]; then
		fail "status 400 sem Location — o SAS recusou o redirect_uri '${PUBLIC_ORIGIN}/login/oauth2/code/gateway-client'. Quase sempre é Postgres semeado com outro PUBLIC_ORIGIN (o seed do gateway-client não reconcilia), NÃO regressão dos Elos 2/6; suba com o volume limpo"
		return
	fi

	if [ "$code" = "302" ]; then
		pass "status 302"
	else
		fail "status $code (esperado 302 para o formulário de login)"
	fi

	if [ "$loc" = "${PUBLIC_ORIGIN}/login" ]; then
		pass "Location: $loc"
	else
		fail "Location: '${loc:-<ausente>}' (esperado exatamente ${PUBLIC_ORIGIN}/login) — X-Forwarded-Host/Proto não chegaram ao auth-server (Elo 2 e/ou Elo 6)"
	fi
}

# ──────────────────────────────────────────────────────────────────────────────
diagnostics() {
	log "Diagnóstico (a stack falhou)"
	compose ps || true
	compose logs --tail=200 gateway authorization-server interface || true
}

cleanup() {
	local rc=$?
	if [ "$STACK_UP" = "1" ]; then
		if [ "$rc" -ne 0 ]; then
			diagnostics
		fi
		if [ "${SMOKE_KEEP_STACK:-0}" = "1" ]; then
			log "SMOKE_KEEP_STACK=1 — stack mantida no ar"
		else
			log "Derrubando a stack (down -v)"
			compose down -v || true
		fi
	fi
	exit "$rc"
}
trap cleanup EXIT

main() {
	require_cmds
	assert_no_running_stack
	prepare_env
	assert_base_compose_ports
	compose_up
	wait_for_login
	assert_login_form
	assert_default_ui_css
	assert_post_login_not_blocked_by_csrf
	assert_authorize_location

	if [ "$FAILURES" -eq 0 ]; then
		log "Smoke-test da topologia de login: OK"
		return 0
	fi
	log "Smoke-test da topologia de login: $FAILURES asserção(ões) reprovada(s)"
	return 1
}

main "$@"
