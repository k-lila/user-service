---
name: new-adr
description: Scaffolda um novo Architecture Decision Record a partir de docs/adr/TEMPLATE.md, com o próximo número sequencial e o slug derivado do título. Use quando uma mudança de contrato/schema/resiliência exige ADR. Ex: /new-adr "cache de sessão dedicado"
arguments: [title]
allowed-tools: Read, Bash, Write
---

Você vai criar um novo arquivo de ADR pré-preenchido. **Não** invente a decisão — deixe
as seções como esqueleto para o `techlead`/humano completarem.

## Próximo número e ADRs existentes

!`echo "### ADRs atuais:"; ls -1 docs/adr/ | grep -E '^ADR-[0-9]' | sort; LAST=$(ls -1 docs/adr/ | grep -oE '^ADR-[0-9]+' | grep -oE '[0-9]+' | sort -n | tail -1); NEXT=$(printf '%03d' $((10#${LAST:-0} + 1))); echo; echo "### Próximo número: ADR-$NEXT"`

## Template

!`cat docs/adr/TEMPLATE.md`

---

## Tarefa

1. Calcule o próximo número `ADR-NNN` (zero-padded, 3 dígitos) a partir do maior existente.
2. Derive o **slug** de `$title`: minúsculas, kebab-case, sem acentos
   (ex.: "Cache de sessão dedicado" → `cache-de-sessao-dedicado`).
3. Crie `docs/adr/ADR-NNN-<slug>.md` copiando a estrutura do `TEMPLATE.md` e pré-preenchendo:
   - Título `# ADR-NNN: $title`
   - **Status:** `proposta`
   - **Data:** data de hoje (rode `date +%F`)
   - **Serviço alvo / Tarefa relacionada:** deixe os placeholders para preenchimento
   - Mantenha as seções **Contexto / Decisão / Consequências / Alternativas consideradas**
     como esqueleto (não invente conteúdo).
4. Confirme o caminho criado e lembre: registrar a decisão também em
   `.claude/memory/decisions.md` e, se mudar contrato, sinalizar `senso-critico`.
   Atualize a lista de ADRs no `CLAUDE.md` (Mapa de documentos) e em
   `docs/CONVENCOES.md` se a decisão formaliza uma invariante.

Antes de gravar, se `$title` estiver vazio, peça o título ao usuário em vez de criar um
ADR sem nome.
