# Skill: test-services

Você é um agente especializado em testes unitários Java. Ao ser invocado, execute **exatamente** a sequência abaixo, sem pular etapas.

---

## PASSO 1 — Descoberta de serviços

Execute:
```bash
find . -name "*.java" | xargs grep -l "@Service" 2>/dev/null
```

Para cada arquivo encontrado, leia o conteúdo completo. Extraia:
- Pacote (`package`)
- Dependências injetadas (construtores e `@Autowired`)
- Assinaturas de todos os métodos públicos
- Exceções lançadas (`throw new`)
- Anotações de cache (`@Cacheable`, `@CachePut`, `@CacheEvict`)

---

## PASSO 2 — Diagnóstico dos testes existentes

Para cada módulo com `@Service`, localize `src/test/` e leia todos os arquivos `.java` presentes.

Para cada serviço, mapeie:
- Quais métodos já têm ao menos um teste
- Quais cenários de erro (exceções) já estão cobertos
- Quais efeitos colaterais (saves, deletes, encodes) já são verificados
- O que está **faltando** — essa é a lista de trabalho

---

## PASSO 3 — Implementação dos testes faltantes

### Regras obrigatórias para todo teste gerado

| Regra | Como aplicar |
|---|---|
| **Isolamento** | `@ExtendWith(MockitoExtension.class)` — nunca `@SpringBootTest` |
| **Padrão AAA** | Seções Arrange / Act / Assert, separadas por linha em branco |
| **Um conceito por teste** | Cada `@Test` verifica exatamente uma condição |
| **Nomes descritivos** | `deve<Resultado>_quando<Condicao>()` em português |
| **Happy path** | Mocks respondem com sucesso; verificar retorno e efeito colateral |
| **Falha / exceção** | `assertThrows(TipoExato.class, () -> servico.metodo(...))` |
| **Efeito colateral** | `verify(mock).metodo(captor)` para saves, deletes, encodes |
| **AOP / Cache** | `@Cacheable`/`@CacheEvict` não disparam sem Spring context — verificar chamada ao repositório |

### Onde criar os arquivos

- Se o arquivo de teste **não existe**: criar em `src/test/java/<mesmo-pacote-do-serviço>/<NomeServiço>Test.java`
- Se **já existe**: adicionar apenas os métodos faltantes no final da classe, preservando os existentes
- **Nunca sobrescrever** um `@Test` existente

### Dependências de teste disponíveis (já no pom.xml via spring-boot-starter-test)

- JUnit 5 (`org.junit.jupiter.api.*`)
- Mockito (`org.mockito.*`, `org.mockito.junit.jupiter.MockitoExtension`)

---

## CONTEXTO FIXO DESTE PROJETO

### Stack
- Java 21, Spring Boot 4.0.x, JUnit 5, Mockito

### Pacotes
- user-service services: `com.users.userservice.services`
- user-service test base: `com.users.userservice.services` (em `src/test/java`)
- authorization-server services: `authorizationserver.services` (classe: `AuthorizationService`)
- authorization-server test base: `authorizationserver.services` (em `src/test/java`)

### Exceções
- Entidade não encontrada (user-service): `com.users.userservice.exceptions.DomainEntityNotFound`
  - Construtor: `new DomainEntityNotFound(User.class, "campo", "valor")`
  - É subclasse de `RuntimeException`
- Usuário não encontrado (authorization-server): `UsernameNotFoundException` (Spring Security)
  - **Atenção:** no `AuthorizationService` do authorization-server, o `UsernameNotFoundException` é lançado dentro de um bloco `try/catch(Exception e)` que captura tudo e relança como `RuntimeException`. O teste para usuário inativo deve esperar `RuntimeException`, não `UsernameNotFoundException`.

### Repositório (user-service)
- Interface: `IUserRepository extends MongoRepository<User, String>`
- Métodos relevantes:
  - `findByEmail(String email)` → `Optional<User>`
  - `findById(String id)` → `Optional<User>`
  - `existsById(String id)` → `boolean`
  - `insert(User user)` → `User`
  - `save(User user)` → `User`
  - `delete(User user)` → `void`
  - `findAll(Pageable pageable)` → `Page<User>`

### Cliente Feign (authorization-server)
- Interface: `IUserClient`
- Método: `getUserByEmail(String email)` → `AuthDTO`

### DTOs relevantes

**UserRequestDTO** (user-service):
- `getName()`, `getEmail()`, `getPasswordHash()`

**UserResponseDTO** (user-service):
- Criado via `UserResponseDTO.toResponseDTO(User user)` (método estático)
- Campos: `id`, `name`, `email`, `registrationDate` (String), `active`

**AuthDTO** (user-service):
- `getId()`, `getEmail()`, `getPasswordHash()`, `getActive()`, `getRoles()` → `Set<String>`

**AuthDTO** (authorization-server):
- `getEmail()`, `getPasswordHash()`, `getActive()`, `getRoles()` → `Set<String>`

### Domínio User (user-service)
- `getId()`, `getName()`, `getEmail()`, `getPasswordHash()`, `getRegistrationDate()` (Instant), `getRoles()` (Set<String>), `getActive()` (Boolean)
- Todos os campos têm setters correspondentes

---

## CATÁLOGO DE CASOS DE TESTE

Use como referência mínima. Implemente os que estiverem faltando.

### RegisterService

```
deveSalvarUsuario_quandoDadosValidos
  - findByEmail retorna Optional.empty()
  - insert retorna User salvo
  - verify(passwordEncoder).encode(...)
  - verify(userRepository).insert(any(User.class))
  - assertNotNull no retorno

deveLancarRuntimeException_quandoEmailJaCadastrado
  - findByEmail retorna Optional.of(userExistente)
  - assertThrows(RuntimeException.class, ...)

deveAtualizarUsuario_quandoIdExiste
  - existsById retorna true
  - findById retorna Optional.of(user) com o mesmo email que o DTO
  - save retorna user atualizado
  - verify(userRepository).save(any(User.class))
  - assertNotNull no retorno

deveLancarDomainEntityNotFound_quandoIdNaoExisteNoUpdate
  - existsById retorna false
  - assertThrows(DomainEntityNotFound.class, ...)

deveLancarRuntimeException_quandoEmailDiferenteNoUpdate
  // ATENÇÃO: este teste documenta o comportamento ATUAL (BUGADO). Ver CLAUDE.md > Bugs Conhecidos.
  // Comportamento correto: lançar exceção apenas se o novo e-mail já pertence a outro usuário.
  - existsById retorna true
  - findById retorna user com email diferente do DTO
  - assertThrows(RuntimeException.class, ...)

deveDeletarUsuario_quandoIdExiste
  - findById retorna Optional.of(user)
  - verify(userRepository).delete(user)

deveLancarDomainEntityNotFound_quandoIdNaoExisteNoDelete
  - findById retorna Optional.empty()
  - assertThrows(DomainEntityNotFound.class, ...)

deveDesativarUsuario_quandoIdExiste
  - findById retorna Optional.of(user com active=true)
  - verify(userRepository).save(any(User.class))
  - capturar o User salvo com ArgumentCaptor e assertFalse(capturado.getActive())

deveLancarDomainEntityNotFound_quandoIdNaoExisteNaDesativacao
  - findById retorna Optional.empty()
  - assertThrows(DomainEntityNotFound.class, ...)
```

**Mock de CacheManager obrigatório em RegisterService:**

`updateUser`, `deleteUser` e `deactivateUser` chamam `cacheManager.getCache("users").evict(...)`. O setup deve estar no `@BeforeEach` da classe inteira para evitar `NullPointerException` em todos esses testes.

`RegisterService` tem **injeção mista**: construtor (`IUserRepository`, `PasswordEncoder`) + campo `@Autowired CacheManager`. Quando `@InjectMocks` usa o construtor com sucesso, o Mockito para ali e não injeta os campos restantes. Use o padrão abaixo — construção manual + `ReflectionTestUtils.setField`:

```java
import static org.mockito.Mockito.lenient;
import org.springframework.cache.Cache; // não javax.cache.Cache
import org.springframework.test.util.ReflectionTestUtils; // disponível via spring-test

@Mock CacheManager cacheManager;
@Mock Cache cache;

// Não use @InjectMocks para RegisterService — use instanciação manual
private RegisterService service;

@BeforeEach
void setUp() {
    service = new RegisterService(userRepository, passwordEncoder);
    ReflectionTestUtils.setField(service, "cacheManager", cacheManager);
    // lenient() é necessário: testes que lançam exceção antes de chegar no cache
    // não usam esse stub e o MockitoExtension (STRICT_STUBS) reclamaria
    lenient().when(cacheManager.getCache("users")).thenReturn(cache);
}
```

> Use este padrão sempre que um serviço combinar injeção por construtor com campos `@Autowired` — `@InjectMocks` sozinho não injeta ambos.

### SearchService

```
// Use PageRequest.of(0, 10) como pageable nos testes — Pageable.unpaged() não implementa
// getPageSize() e quebra se o service chamar esse método (ex: no logger).
deveRetornarPaginaDeUsuarios_quandoExistemRegistros
  - findAll(pageable) retorna new PageImpl<>(List.of(user))
  - count() retorna 1L
  - assertFalse(resultado.isEmpty())

deveRetornarPaginaVazia_quandoNaoExistemRegistros
  - findAll(pageable) retorna new PageImpl<>(List.of())
  - count() retorna 0L
  - assertTrue(resultado.isEmpty())

deveRetornarUsuario_quandoIdValido
  - findById retorna Optional.of(user)
  - assertNotNull no retorno
  - assertEquals(user.getId(), resultado.getId())

deveLancarDomainEntityNotFound_quandoIdInvalido
  - findById retorna Optional.empty()
  - assertThrows(DomainEntityNotFound.class, ...)

deveRetornarUsuario_quandoEmailValido
  - findByEmail retorna Optional.of(user)
  - assertNotNull no retorno
  - assertEquals(user.getEmail(), resultado.getEmail())

deveLancarDomainEntityNotFound_quandoEmailNaoExiste
  - findByEmail retorna Optional.empty()
  - assertThrows(DomainEntityNotFound.class, ...)
```

### AuthenticationService (user-service)

```
deveRetornarAuthDTO_quandoUsuarioAtivoExiste
  - findByEmail retorna Optional.of(user com active=true)
  - assertNotNull no retorno
  - assertEquals em id, email, passwordHash, roles

deveLancarDomainEntityNotFound_quandoEmailNaoExiste
  - findByEmail retorna Optional.empty()
  - assertThrows(DomainEntityNotFound.class, ...)

deveLancarDomainEntityNotFound_quandoUsuarioInativo
  - findByEmail retorna Optional.of(user com active=false)
  - assertThrows(DomainEntityNotFound.class, ...)
```

### AuthorizationService (authorization-server)

// Método a testar: service.loadUserByUsername(email)

```
deveRetornarUserDetails_quandoUsuarioAtivoExiste
  - userClient.getUserByEmail retorna AuthDTO com active=true e roles=Set.of("USER")
  - assertNotNull no retorno
  - assertEquals(email, userDetails.getUsername())
  - assertTrue(userDetails.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")))

deveMapearMultiplasRoles_corretamente
  - AuthDTO com roles=Set.of("USER", "ADMIN")
  - authorities deve conter ROLE_USER e ROLE_ADMIN

deveLancarRuntimeException_quandoUsuarioInativo
  - userClient retorna AuthDTO com active=false
  - ATENÇÃO: UsernameNotFoundException é lançado dentro do try/catch(Exception e)
    e capturado, sendo relançado como RuntimeException
  - assertThrows(RuntimeException.class, ...)

deveLancarRuntimeException_quandoClienteLancaExcecao
  - when(userClient.getUserByEmail(any())).thenThrow(new RuntimeException("timeout"))
  - assertThrows(RuntimeException.class, ...)
  - assertTrue(excecao.getMessage().contains("Erro de comunicação"))
```

---

## PASSO 4 — Execução e correção

Após escrever todos os arquivos de teste, rode em cada módulo modificado:

```bash
mvn test -f user-service/pom.xml 2>&1 | tail -n 100
mvn test -f authorization-server/pom.xml 2>&1 | tail -n 100
```

Se algum teste falhar:
1. Leia o stack trace completo
2. Identifique a causa raiz (assinatura errada, mock incorreto, comportamento real diferente do esperado)
3. Corrija o teste — não altere código de produção, a menos que seja um bug confirmado
4. Rode novamente até obter `BUILD SUCCESS` em ambos os módulos

Reporte ao final: quantos testes foram adicionados por serviço e o resultado final dos dois módulos.

---

## PASSO 5 — Manutenção de testes existentes

Execute este passo quando o PASSO 2 detectar arquivos de teste já existentes. O objetivo é garantir que os testes acompanhem mudanças no código de produção.

Para cada teste existente, verifique:

**1. Assinaturas alteradas** — o método de produção mudou nome, parâmetros ou tipo de retorno?
- Se sim: atualizar o teste para usar a nova assinatura

**2. Testes de bug corrigido** — testes marcados com `// ATENÇÃO: comportamento ATUAL (BUGADO)`
- Releia o código de produção: o bug foi corrigido?
- Se sim: inverter o mock e as assertions para refletir o comportamento correto; remover a marcação de bug
- Se não: manter o teste sem alteração

**3. Métodos removidos** — o método de produção foi excluído?
- Adicionar comentário `// MÉTODO REMOVIDO — revisar se cenário ainda é relevante` e não remover o teste

**4. Novos métodos públicos sem teste** — adicionar conforme o catálogo (PASSO 3)

Após qualquer edição, rodar `mvn test` e corrigir falhas antes de reportar.
