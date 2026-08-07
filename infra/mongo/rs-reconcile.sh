#!/usr/bin/env bash
# Reconciliação do replica set rs0 (ADR-024) — substitui o `rs.initiate` de 3 membros literais.
#
# POR QUE NÃO BASTA PARAMETRIZAR A LISTA. `rs.initiate` roda UMA VEZ por volume: num volume já
# iniciado ele devolve AlreadyInitialized e não faz nada. Se o piso mínimo subisse com 1 membro,
# ligar `--profile ha` depois NÃO adicionaria membro nenhum — o replica set ficaria eternamente
# com um nó enquanto dois containers Mongo ociosos rodariam ao lado. Crescer exige `rs.reconfig`.
#
# COMO DECIDE QUEM ENTRA. Por resolução DNS, não por variável de ambiente. Um nó Mongo que não
# está no ar não tem registro no DNS do Compose; um que está, tem. Assim `--profile ha` sozinho
# basta: os containers existem, o DNS os resolve, e eles entram no replica set. Sem env para
# esquecer de setar junto com o profile — a classe de bug que o docs/CONFIG.md registra como
# "variável inerte" (19 casos encontrados numa varredura).
#
# SÓ CRESCE, NUNCA ENCOLHE — deliberado. Remover membro com base em lookup que falhou seria
# perigoso: uma falha transitória de DNS durante um restart derrubaria o quorum do replica set.
# Encolher é operação manual e deliberada (rs.remove), documentada em docs/CONFIG.md.
set -euo pipefail

PRIMARY="${MONGO_PRIMARY_HOST:-mongo-1:27017}"
CANDIDATES="${MONGO_RS_CANDIDATES:-mongo-1:27017 mongo-2:27017 mongo-3:27017}"

reachable=()
for candidate in $CANDIDATES; do
  host="${candidate%%:*}"
  if getent hosts "$host" >/dev/null 2>&1; then
    reachable+=("\"$candidate\"")
  fi
done

if [ ${#reachable[@]} -eq 0 ]; then
  echo "[rs-reconcile] ERRO: nenhum nó Mongo resolvível entre: $CANDIDATES" >&2
  exit 1
fi

desired_js="[$(IFS=,; echo "${reachable[*]}")]"
echo "[rs-reconcile] membros alcançáveis: $desired_js"

exec mongosh --host "$PRIMARY" \
  -u "$MONGO_USER" -p "$(cat /run/secrets/MONGO_PASSWORD)" \
  --authenticationDatabase admin \
  --quiet \
  --eval "
    const desired = $desired_js;

    let initialized = true;
    try {
      rs.status();
    } catch (e) {
      if (e.codeName === 'NotYetInitialized') { initialized = false; }
      else { throw e; }
    }

    if (!initialized) {
      rs.initiate({
        _id: 'rs0',
        members: desired.map((host, i) => ({ _id: i, host: host }))
      });
      print('[rs-reconcile] rs0 iniciado com ' + desired.length + ' membro(s)');
      quit(0);
    }

    const cfg = rs.conf();
    const present = cfg.members.map(m => m.host);
    const missing = desired.filter(h => !present.includes(h));

    if (missing.length === 0) {
      print('[rs-reconcile] rs0 já contém os ' + present.length + ' membro(s) alcançáveis');
      quit(0);
    }

    // rs.reconfig exige version incrementado; o _id de cada membro tem de ser inédito na config.
    let nextId = Math.max(...cfg.members.map(m => m._id)) + 1;
    for (const host of missing) {
      cfg.members.push({ _id: nextId++, host: host });
    }
    cfg.version++;
    rs.reconfig(cfg);
    print('[rs-reconcile] rs0 expandido para ' + cfg.members.length + ' membro(s): +' + missing.join(', '));
  "
