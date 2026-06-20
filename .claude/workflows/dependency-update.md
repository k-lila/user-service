# Workflow: Atualização de dependências

> Workflow de **higiene de dependências** (não entrega feature de domínio). Mantém a base
> **segura e compatível** para que o produto evolua sem trombar com dívida de versão:
> CVEs, EOL de versões e upgrades de framework. O viés é mudança pequena e auditável —
> nunca "atualiza tudo de uma vez".

## Gatilho
CVE reportado, dependência atrasada/EOL, ou janela de atualização de versões. Pode mirar
um módulo (`{service_target}`) ou o front (`login-interface`).

## Sequência

### FASE 1 — Auditoria e plano (`dependency-steward`)
Inventário (`mvn versions:display-dependency-updates`, `npm audit`/`outdated`); prioriza
por severidade/exploitabilidade real. Relatório pré-mudança: por dependência, motivo,
versão atual→alvo, major/minor/patch, breaking changes conhecidos, arquivos a tocar.
**Aguarda confirmação** em bump major ou >3 arquivos.

### FASE 2 — Aplicação do bump (`dependency-steward`)
Edita **apenas** manifestos (`pom.xml`/BOM/`<properties>`, `package.json`). Valida build:
`mvn -pl <módulo> -am verify` (dispara o gate JaCoCo) / `npm run build`.
- Build quebra por mudança de API da lib → **devolve ao `techlead`** com o diagnóstico
  (`needs_code_change: true`). O steward não refatora código.

### FASE 3 — Regressão (`qa-tester`)
Roda a suíte dos módulos afetados. **Portão inegociável:** nenhum bump segue sem
regressão verde. Bump não altera contrato → foco total em regressão.

### FASE 4 — Revisão de segurança (`security-reviewer`, **condicional**)
Obrigatória se o bump é de dependência de segurança (Spring Security, OAuth, libs de
JWT/serialização, BCrypt) ou fechou um CVE. Confirma que o upgrade não regrediu controle
nem reintroduziu CVE.
- `REJECTED` com BLOQUEADOR → devolve ao `dependency-steward` (máx. 2x) → humano.
- `APPROVED` → registra o bump e qualquer dívida em `.claude/memory/decisions.md`, e move
  o CVE de `docs/SECURITY.md` (gap) para resolvido quando aplicável.

## Regra inviolável
Bump que **exige mudança de código** deixa de ser tarefa do `dependency-steward` e vira
feature/bugfix conduzida pelo `techlead`. Bump de dependência de segurança **sempre**
passa pelo `security-reviewer`.
