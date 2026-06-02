---
name: suggest-tests
description: Mapeia caminhos testáveis de uma classe do user-service e gera os testes faltantes seguindo as convenções do projeto (unitários Mockito, @WebMvcTest para controllers, Testcontainers para integração/cache). Use quando o usuário pede para criar testes, analisar cobertura ou identificar lacunas. Ex: /suggest-tests AuthenticationService
arguments: [target]
allowed-tools: Read, Bash, Edit, Write
---

## Arquivo fonte do alvo

!`FILE=$(find /home/k-lila/diretorio/user-service/user-service/src/main -name "$target.java" 2>/dev/null | head -1); [ -n "$FILE" ] && cat "$FILE" || echo "AVISO: arquivo fonte não encontrado para '$target'. Verifique o nome da classe."`

## Arquivo de teste existente (se houver)

!`FILE=$(find /home/k-lila/diretorio/user-service/user-service/src/test -name "${target}Test.java" 2>/dev/null | head -1); [ -n "$FILE" ] && cat "$FILE" || echo "Nenhum arquivo de teste encontrado para '${target}Test'. Será necessário criar."`

---

## Convenções de teste do projeto

### Tipo 1 — Unitário (Mockito puro, sem contexto Spring)

Quando usar: lógica de negócio pura sem I/O real. **IMPORTANTE:** `@Cacheable`, `@Transactional` e outros proxies Spring são ignorados neste tipo — não teste comportamento de cache aqui.

!`cat /home/k-lila/diretorio/user-service/user-service/src/test/java/com/users/userservice/services/AuthenticationServiceTest.java`

---

### Tipo 2 — Controller (@WebMvcTest + JWT simulado)

Quando usar: status HTTP, autorização (ROLE_USER / ROLE_ADMIN / sem token), extração de claims JWT, serialização da resposta.

!`head -100 /home/k-lila/diretorio/user-service/user-service/src/test/java/com/users/userservice/controller/UserControllerTest.java`

Padrões obrigatórios:
- `@WebMvcTest(XController.class)` + `@Import({SecurityConfig.class, GlobalExceptionHandler.class})`
- Em Spring Boot 4.0 o slice `@WebMvcTest` não carrega essas classes automaticamente — o `@Import` é mandatório
- Autenticação: `SecurityMockMvcRequestPostProcessors.jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))`
- Claims customizados: `.jwt(jwt -> jwt.claim("userID", "id-1"))`

---

### Tipo 3 — Integração com Redis e MongoDB reais (Testcontainers)

Quando usar: fluxos que envolvem MongoDB, comportamento de cache (`@Cacheable`, evicção manual via `CacheService`), dados que precisam persistir entre chamadas.

Base obrigatória — todo teste de integração estende esta classe:

!`cat /home/k-lila/diretorio/user-service/user-service/src/test/java/com/users/userservice/integration/AbstractIntegrationTest.java`

Exemplo completo — referência para testes de cache:

!`cat /home/k-lila/diretorio/user-service/user-service/src/test/java/com/users/userservice/integration/CacheIntegrationTest.java`

Padrões obrigatórios para cache:
- Para verificar **população**: chamar o método duas vezes (1ª = miss + popula cache, 2ª = hit); depois `assertNotNull(cache.get(key))`
- Para verificar **evicção**: chamar após operação de mutação e `assertNull(cache.get(key))`
- Para verificar **dados corretos**: `((TipoDTO) cache.get(key).get()).getCampo()`
- Para limpar entre testes: `AbstractIntegrationTest.limparRedis()` chama `flushDb()` — cobre todos os caches Redis
- Se o alvo usa `authByEmail`, adicionar `cacheManager.getCache("authByEmail").clear()` no `limparRedis()` de `AbstractIntegrationTest` por simetria

---

## Tarefa

Com base nos arquivos acima, execute as etapas na ordem:

**Etapa 1 — Mapeamento de caminhos testáveis**

Liste todos os caminhos da classe `$target`, agrupados por categoria:
- Caminhos felizes (retornos normais e seus dados esperados)
- Caminhos de erro (exceções lançadas, condições de guarda, usuário inativo)
- Casos de borda (cache miss/hit, evicção após mutação, dados obsoletos no cache, exceções não cacheadas)

**Etapa 2 — Diagnóstico de cobertura**

Para cada caminho, indique:
- ✅ Coberto — qual teste cobre
- ❌ Descoberto — qual tipo de teste seria adequado (unitário / controller / integração)

**Etapa 3 — Confirmação antes de implementar**

Apresente o diagnóstico e pergunte ao usuário se deve implementar os testes descobertos.
Se o alvo envolver mais de 3 arquivos, liste quais serão criados ou modificados e aguarde confirmação.

**Etapa 4 — Implementação (somente após confirmação)**

Ao escrever os testes:
- Siga rigorosamente os padrões de cada tipo descritos acima
- Nomes de método no padrão: `deve[Comportamento]_quando[Condição]()`
- Sem comentários explicativos no corpo dos testes — o nome do método já documenta
- Ao final, rode `mvn test -Dtest="NomeDoArquivoDeTeste"` para confirmar BUILD SUCCESS
