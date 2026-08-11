# ADR-026: Revogação ativa na troca de senha e de e-mail

- **Status:** aceita
- **Data:** 2026-08-10
- **Serviço alvo:** user-service (grava o epoch) · gateway e authorization-server (já o leem)
- **Tarefa relacionada:** G15

## Contexto

`RegisterService.updateUser` regravava o `passwordHash` e **não invalidava nada**: sem epoch de
revogação, sem derrubar o access token vivo, sem bloquear o refresh, sem tocar a sessão do IdP nem a
do gateway. Consequência: **trocar a senha não expulsava quem já estava dentro** — inclusive um
atacante com sessão ativa, que é precisamente o caso de uso de trocar a senha. O titular que suspeita
de comprometimento fechava a porta sem tê-la fechado.

O gap foi identificado durante a [ADR-025](ADR-025-revalidacao-estado-emissao.md) e deliberadamente
mantido fora do escopo dela, para não misturar duas correções de segurança no mesmo commit. Ficou
registrado como **G15** no `docs/SECURITY.md` da época — que hoje documenta só estado corrente — e
no inventário das sete cópias do estado de autorização em
[docs/CONVENCOES.md](../CONVENCOES.md), onde a lacuna consta como fechada por esta ADR.

Duas perguntas ficaram abertas na ocasião, ambas de produto, e são o que este ADR resolve:

1. A troca de senha deve deslogar **o próprio autor** da troca?
2. A troca de **e-mail**, que acontece no mesmo método, entra junto?

O contexto que torna (2) não-óbvio: a ADR-025 já desliga quem troca o e-mail, mas **por efeito
colateral** — o `principal_name` da sessão do IdP continua sendo o e-mail antigo, a próxima
autorização não o encontra e a sessão morre. Isso está ratificado como P-01 (`ADR-025:228-245`), com
o custo assumido de o desligamento levar até **~1h**, que é a vida do refresh token.

## Decisão

**1. `updateUser` grava o epoch de revogação quando a senha muda OU o e-mail muda.** Mudar só o nome
não revoga nada.

```java
boolean senhaAlterada = userDTO.getPassword() != null && !userDTO.getPassword().isBlank();
boolean emailAlterado = !oldMail.equals(userDTO.getEmail());
// … mutações, save e evictions/puts de cache já existentes …
if (senhaAlterada || emailAlterado) {
    tokenRevocationService.revoke(userID);
}
```

A chamada segue o padrão consolidado do [ADR-017](ADR-017-revogacao-ativa-token.md): **última
operação de estado antes do log**, depois da persistência e do cache, **sem** try/catch no chamador
— o fail-open vive dentro de `TokenRevocationService.revoke`. Idêntico a `deactivateUser`,
`deleteUser` e `AdminService.updateUserRoles`.

**2. O autor da troca também é deslogado.** `revoke(userID)` é global por titular e não distingue a
sessão de quem fez a troca das demais. Deslogar todo mundo, inclusive o autor, é o comportamento de
mercado e é o único compatível com o mecanismo do ADR-017 — cujo propósito declarado é justamente
"revogar **todos** os tokens de um usuário".

**3. A troca de e-mail entra.** Não por ser um novo mecanismo de desligamento (a P-01 já desligava),
mas para colapsar a latência — ver Consequências.

## Consequências

### Positivas

- **G15 fechado.** As cópias **#4** (claims do access token) e **#5** (refresh token) do inventário
  das sete cópias passam a ser invalidadas na troca de credencial. Por consequência, a **#7** (sessão
  do gateway) morre no `RevocationWebFilter` e a **#3** (sessão do IdP) morre na próxima autorização,
  pela re-derivação da ADR-025.
- **Troca de e-mail: o desligamento cai de ~1h para ~segundos.** Antes, a sessão do IdP morria mas o
  access token (5m) e o refresh (60m) seguiam válidos, e o titular trabalhava normalmente até o
  refresh expirar. Com o epoch, o gateway responde 401 e invalida a sessão no próximo request.
- A cobertura de teste da revogação deixa de ser só de integração: `RegisterServiceTest` passa a ter
  os primeiros `verify(tokenRevocationService)` do arquivo — o mock existia desde sempre e **nunca
  fora verificado**. O ramo `setPasswordHash` de `updateUser`, que não tinha cobertura unitária
  alguma, passa a ter.

### O que este ADR **não** muda — premissa corrigida contra o código

Uma leitura plausível é que, com o epoch, a troca de e-mail passaria a aparecer no log como
`REVOKED_EPOCH` em vez de `NOT_FOUND`, estreitando a ambiguidade registrada em `ADR-025:254-256`.
**Isso é falso**, e verificá-lo contra o código (não contra a documentação) é o que evita propagar o
erro:

`AuthorizationEndpointRevalidationFilter` só emite `REVOKED_EPOCH` no **caminho degradado** —
o `catch (AuthenticationException)` das linhas 195-205, alcançado quando o user-service está
indisponível. No caminho normal, a re-derivação chama `loadUserByUsername(e-mail antigo)`, recebe
`UsernameNotFoundException` e devolve `NOT_FOUND` (linhas 187-194).

Portanto: **a ambiguidade do `NOT_FOUND` permanece intacta.** Titular eliminado e titular que trocou
o e-mail continuam indistinguíveis naquela linha de log, e quem a usar como evidência de que a
eliminação funcionou continua precisando cruzá-la com outro sinal. O que o epoch altera é a
**latência** do desligamento, não o motivo registrado.

### Negativas e custos assumidos

- **O autor da troca cai junto.** Quem troca a própria senha é deslogado de todas as sessões e
  precisa refazer o login. Aceito, e é o comportamento esperado pelo usuário.
- A marca de revogação tem TTL de 75 min (`security.revocation.ttl`), ≥ a vida máxima de um refresh
  token — passado esse prazo não há token vivo anterior à revogação e a chave se auto-limpa.
- A checagem permanece **fail-open** em todas as três camadas: outage de Redis não bloqueia
  autenticação, mas também não revoga. Inalterado em relação ao ADR-017.
- `updateUser` não distingue mais "trocou o nome" de "não mudou nada" para fins de revogação — ambos
  não revogam. Se no futuro algum campo de autorização entrar nesse método, a condição precisa crescer.

### Emenda à P-01 da ADR-025

A P-01 registra que o desligamento por troca de e-mail é "atrasado e não-determinístico", com o
titular trabalhando por até ~1h, e conclui que o item de backlog do SPA **não pode prometer**
correlacionar a mensagem de logout ao ato de trocar o e-mail, "porque no instante em que o titular
cai o ato já ficou para trás". **Com este ADR essa restrição deixa de valer:** o desligamento passa a
ser de ~segundos e a correlação volta a ser viável.

### Contrato de API

Sem rota nova ou removida, sem mudança de payload. **Um comportamento observável muda:**
`PUT /v1/users` com senha nova ou e-mail novo passa a invalidar os tokens vivos do titular — o
request seguinte do SPA recebe 401 e a sessão é encerrada. `PUT /v1/users` que altera só o nome
continua sem qualquer efeito sobre a sessão.

### Sem oitava cópia

Este ADR **não** introduz cópia nova do estado de autorização. Ele altera o mecanismo de invalidação
de cópias já inventariadas, e a tabela de `docs/CONVENCOES.md` é atualizada no mesmo commit,
conforme a invariante declarada lá.

### Fora de escopo

`AuditAction.PASSWORD_CHANGE` **não** foi criado — a troca segue auditada como `UPDATE`. Acrescentar
valor ao enum é mudança do schema da trilha LGPD (coleção `auditLogs`) e exigiria ADR próprio.

## Alternativas consideradas

**Revogar só na troca de senha, deixando o e-mail com o comportamento da P-01.** Fecharia o G15
exatamente como `docs/SECURITY.md` o descreve e preservaria a P-01 sem emenda. Rejeitada porque
deixaria dois comportamentos de invalidação distintos dentro do mesmo método — imediato para senha,
eventual (~1h) para e-mail — sem que nada no código sinalize a assimetria. É a classe de
inconsistência que vira bug reportado seis meses depois.

**Não deslogar o autor da troca.** Exigiria revogação por *sessão* em vez de por *titular*. O epoch
do ADR-017 é por titular por desenho, precisamente porque uma denylist de `jti` não cobre "revogar
todos os tokens de um usuário". Implementar isso significaria um segundo mecanismo de revogação
convivendo com o primeiro. Rejeitada.

**Invalidar a sessão do IdP diretamente, em vez de por epoch.** O user-service não tem acesso à
sessão do authorization-server (separação rígida — o auth-server só é alcançado por Feign, e no
sentido inverso). Exigiria endpoint interno novo, ampliando a superfície da
[ADR-006](ADR-006-canal-interno-isolado.md). O epoch já é a fonte única compartilhada pelos três
serviços; reusá-lo é o caminho de menor superfície.
