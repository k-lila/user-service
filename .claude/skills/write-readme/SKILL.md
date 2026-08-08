---
name: write-readme
description: Escreve ou sincroniza o README de um alvo (raiz ou módulo). Se já existe README.md, audita cada afirmação verificável contra o estado real do repo (compose, .env.example, CI, portas, comandos) e corrige só o que divergiu; se não existe, gera a partir do esqueleto padrão do projeto. Use quando pedirem para criar/atualizar README ou quando a doc de execução parecer defasada. Ex: /write-readme ou /write-readme notification-service
arguments: [alvo]
allowed-tools: Read, Edit, Write, Bash, Grep
---

Você vai produzir ou corrigir um README. O README deste projeto é **para humano**: pré-requisitos,
execução e roteiros operacionais. Referência de API, variáveis, convenções, testes e segurança
vivem em `docs/` — **linke, não duplique** (ver o "Mapa de documentos" do `CLAUDE.md`).

> `alvo` (opcional, default: raiz do repo): caminho do módulo cujo README será escrito
> (ex.: `notification-service`, `login-interface`). Vazio = `README.md` da raiz.

## Modo de operação

!`ALVO="${alvo:-.}"; TARGET="${ALVO%/}/README.md"; TARGET="${TARGET#./}"; if [ -f "$TARGET" ]; then echo "MODO: SINCRONIZAR — $TARGET existe ($(wc -l < "$TARGET") linhas)"; else echo "MODO: GERAR — $TARGET não existe"; fi; echo "Alvo: $ALVO"`

## README atual (vazio se o modo for GERAR)

!`ALVO="${alvo:-.}"; TARGET="${ALVO%/}/README.md"; TARGET="${TARGET#./}"; [ -f "$TARGET" ] && cat -n "$TARGET" || echo "(não existe)"`

## Estado real do repositório

### Serviços e portas publicadas (compose base vs. override vs. deploy)
!`for f in docker-compose.yml docker-compose.override.yml docker-compose.deploy.yml; do [ -f "$f" ] && { echo "--- $f"; grep -nE '^  [a-z0-9-]+:|^\s+- "[0-9.:]+[0-9]+:' "$f"; echo; }; done`

### Variáveis do .env.example
!`[ -f .env.example ] && grep -vE '^\s*$' .env.example | grep -nE '^[A-Z_]+=|^#' | head -80 || echo "(sem .env.example)"`

### Jobs e passos do CI
!`[ -f .github/workflows/ci.yml ] && grep -nE '^\s{2}[a-z-]+:|name:|run:|matrix' .github/workflows/ci.yml || echo "(sem workflow de CI)"`

### Módulos Maven, scripts npm e scripts de infra
!`echo "--- módulos maven:"; grep -oE '<module>[^<]+</module>' pom.xml 2>/dev/null || ls -d */pom.xml 2>/dev/null; echo; echo "--- scripts npm (login-interface):"; command -v jq >/dev/null && jq -r '.scripts | to_entries[] | "  \(.key): \(.value)"' login-interface/package.json 2>/dev/null || grep -A15 '"scripts"' login-interface/package.json 2>/dev/null; echo; echo "--- scripts de infra:"; find infra -name '*.sh' -type f 2>/dev/null | sort`

### Versões declaradas (o README costuma citá-las)
!`grep -m1 -E '<java.version>|<version>' pom.xml 2>/dev/null; grep -m1 -A2 'spring-boot-starter-parent\|spring-cloud' pom.xml 2>/dev/null | head -20; node -e 'const p=require("./login-interface/package.json");console.log("react:",p.dependencies?.react,"vite:",p.devDependencies?.vite)' 2>/dev/null`

### ADRs e docs existentes (destino dos links)
!`ls -1 docs/*.md docs/adr/ADR-*.md 2>/dev/null`

---

## Tarefa

Identifique o modo no primeiro bloco acima e siga **só** o roteiro correspondente.

### MODO SINCRONIZAR

Auditoria cirúrgica. A prosa existente é resultado de aprendizado acumulado — **preserve-a**.
Só altere o que estiver comprovadamente errado contra os blocos de estado acima.

1. Percorra o README e separe cada afirmação em **verificável** (porta, nome de serviço,
   variável de ambiente, comando, caminho de arquivo, versão, nome de job do CI, link para
   `docs/`) ou **narrativa** (racional, avisos, explicação de "por quê").
2. Cheque cada afirmação verificável contra o estado real. Cheque também os **links relativos**:
   arquivo linkado que não existe é divergência.
3. Monte a tabela de divergências `{ linha, afirmação no README, estado real, correção }`
   e **apresente-a antes de editar**.
4. Aplique só as correções da tabela, com `Edit`. Não reescreva parágrafos corretos, não
   reordene seções, não "melhore" estilo, não converta narrativa em bullet.
5. **Nunca apague um aviso operacional** por não conseguir verificá-lo — avisos sobre efeitos
   colaterais (ex.: script que sobrescreve segredos, ordem obrigatória de subida, volume que
   precisa ser zerado) são conhecimento que o código não expressa. Se um aviso parecer obsoleto,
   liste-o como pergunta ao humano em vez de removê-lo.
6. Se um item estiver **faltando** (serviço/variável/comando novo que o README ignora),
   proponha o texto e onde encaixar — sem inventar racional que você não confirmou no repo.
7. Se nada divergir, diga **"README sincronizado — nenhuma divergência"** e não edite nada.

### MODO GERAR

Esqueleto enxuto, coerente com o restante do projeto. Só afirme o que confirmou nos blocos
acima; onde faltar informação, deixe `<!-- TODO: … -->` explícito em vez de preencher por
analogia com outro módulo.

Seções, nesta ordem:

1. `# <nome>` + 1–3 linhas: o que o serviço faz e seu papel no ecossistema.
2. **Pré-requisitos** — só o que este alvo exige de fato.
3. **Execução** — via Docker (aponte para o `README.md` da raiz, não duplique o roteiro) e
   local (`mvn spring-boot:run` / `npm run dev`), com as dependências de subida do serviço.
4. **Configuração** — tabela só das variáveis próprias do módulo; o resto linka
   `docs/CONFIG.md`.
5. **Endpoints** (se houver) — tabela curta; detalhe linka `docs/SERVICOS.md`.
6. **Testes** — comando real do módulo, extraído dos blocos acima; estratégia linka
   `docs/TESTES.md`.

Nos dois modos: português do Brasil, tom do README raiz, caminhos sempre relativos à raiz do
repo. Ao terminar, se o alvo for a raiz e a mudança tocar contrato/roteiro de subida,
lembre de verificar se `CLAUDE.md` e `docs/` contam a mesma história.
