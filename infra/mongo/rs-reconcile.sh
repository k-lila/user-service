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
# Encolher é operação manual e deliberada (rs.remove), documentada em docs/CONFIG.md. O que o
# script FAZ a respeito é detectar quem encolheu na ordem errada: ver a guarda de maioria abaixo.
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

    // GUARDA DE MAIORIA. A config do rs0 vive no VOLUME, não no compose: depois de um ciclo
    // --profile ha ela fica com 3 membros e continua com 3 depois que mongo-2/3 somem. Sozinho
    // numa config de 3 votantes, mongo-1 não alcança maioria, assume SECONDARY e RECUSA escrita
    // — com leitura funcionando, healthcheck verde e nada nos logs apontando a causa. Este
    // script chegava aqui, calculava missing = [] e saía com SUCESSO, ratificando o estado.
    const majority = Math.floor(present.length / 2) + 1;
    if (desired.length < majority) {
      const unreachable = present.filter(h => !desired.includes(h));
      print('[rs-reconcile] ERRO: rs0 tem ' + present.length + ' membro(s) configurado(s) e só ' +
            desired.length + ' alcançável(is) — abaixo da maioria de ' + majority + '.');
      print('[rs-reconcile] Sem maioria não há PRIMARY: leitura funciona, escrita falha com');
      print('[rs-reconcile] NotWritablePrimary. É o estado de quem desligou o --profile ha sem');
      print('[rs-reconcile] remover os membros antes. Dois caminhos:');
      print('[rs-reconcile]   (a) voltar ao perfil completo: docker compose --profile ha up -d');
      print('[rs-reconcile]   (b) encolher de verdade — com o replica set AINDA com maioria,');
      print('[rs-reconcile]       rodar no PRIMARY e só então desligar o profile:');
      unreachable.forEach(h => print('[rs-reconcile]         rs.remove(\"' + h + '\")'));
      // Falhar (em vez de avisar) é deliberado: user-service depende deste job com
      // service_completed_successfully, então o exit != 0 impede que a aplicação suba contra um
      // Mongo somente-leitura e aceite cadastros que morreriam em 500. O restart: on-failure
      // cobre o caso transitório — na subida do --profile ha, se mongo-2 ainda não resolve no
      // DNS a guarda dispara, o job repete, e passa assim que o nó aparece.
      quit(1);
    }

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
