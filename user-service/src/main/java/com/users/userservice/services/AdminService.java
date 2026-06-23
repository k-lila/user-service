package com.users.userservice.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.users.userservice.domain.AuditLog;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.AdminUserResponseDTO;
import com.users.userservice.dtos.AuditLogResponseDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.SelfRoleRevocationException;
import com.users.userservice.repository.IAuditLogRepository;
import com.users.userservice.repository.IUserRepository;

/**
 * Camada de serviço da superfície administrativa do user-service (ADR-014): listagem
 * incluindo inativos, consulta da trilha de auditoria LGPD e gestão de roles. Dedicado e
 * separado de {@code SearchService}/{@code RegisterService} (que têm contrato 100% não-admin).
 */
@Service
public class AdminService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminService.class);
    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";

    /** Teto de tamanho de página para os endpoints de auditoria (AC-05) — impede drenagem da trilha LGPD. */
    public static final int MAX_AUDIT_PAGE_SIZE = 100;

    private final IUserRepository userRepository;
    private final IAuditLogRepository auditLogRepository;
    private final CacheService cacheService;
    private final MongoTemplate mongoTemplate;
    private final TokenRevocationService tokenRevocationService;

    public AdminService(
            IUserRepository userRepository,
            IAuditLogRepository auditLogRepository,
            CacheService cacheService,
            MongoTemplate mongoTemplate,
            TokenRevocationService tokenRevocationService) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.cacheService = cacheService;
        this.mongoTemplate = mongoTemplate;
        this.tokenRevocationService = tokenRevocationService;
    }

    /**
     * Listagem administrativa com filtros combináveis (active/name/email), incluindo
     * usuários inativos — diferente da listagem pública (ADR-001).
     */
    public Page<AdminUserResponseDTO> listAllUsers(Boolean active, String name, String email, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();
        if (active != null) {
            filters.add(Criteria.where("active").is(active));
        }
        if (name != null && !name.isBlank()) {
            filters.add(Criteria.where("name").regex(Pattern.quote(name), "i"));
        }
        if (email != null && !email.isBlank()) {
            filters.add(Criteria.where("email").regex(Pattern.quote(email), "i"));
        }

        Query baseQuery = new Query();
        if (!filters.isEmpty()) {
            baseQuery.addCriteria(new Criteria().andOperator(filters.toArray(new Criteria[0])));
        }

        long total = mongoTemplate.count(baseQuery, User.class);
        Query pagedQuery = Query.of(baseQuery).with(pageable);
        List<User> users = mongoTemplate.find(pagedQuery, User.class);
        Page<AdminUserResponseDTO> page = new PageImpl<>(
                users.stream().map(AdminUserResponseDTO::toResponseDTO).toList(),
                pageable,
                total
        );
        LOGGER.info(
            "| ADMIN | listagem | página: {}x{} | total: {}",
            pageable.getPageSize(),
            pageable.getPageNumber(),
            page.getTotalElements()
        );
        return page;
    }

    /**
     * Leitura administrativa de um titular por ID (ADR-016 — fix do G1/IDOR). Diferente da
     * leitura pública (removida do {@code UserController}), <b>inclui inativos</b> — coerente com
     * a listagem admin, que expõe soft-deleted. 404 ({@link DomainEntityNotFound}) só se ausente.
     * Sem cache: evita colisão de tipo com {@code usersById} (que guarda {@code UserResponseDTO}).
     */
    public AdminUserResponseDTO findById(String id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> {
                LOGGER.warn("| ADMIN | busca por ID | não encontrado | ID: {}", id);
                return new DomainEntityNotFound(User.class, "ID", id);
            });
        LOGGER.info("| ADMIN | busca por ID | encontrado | ID: {}", user.getId());
        return AdminUserResponseDTO.toResponseDTO(user);
    }

    /**
     * Leitura administrativa de um titular por e-mail (ADR-016 — fix do G1/IDOR). <b>Inclui
     * inativos</b>; 404 ({@link DomainEntityNotFound}) só se ausente. Sem cache (ver {@link #findById}).
     */
    public AdminUserResponseDTO findByEmail(String email) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> {
                LOGGER.warn("| ADMIN | busca por email | não encontrado");
                return new DomainEntityNotFound(User.class, "Email", email);
            });
        LOGGER.info("| ADMIN | busca por email | encontrado | ID: {}", user.getId());
        return AdminUserResponseDTO.toResponseDTO(user);
    }

    /** Trilha de auditoria de um titular específico (AC-04, AC-06). */
    public Page<AuditLogResponseDTO> getAuditLogsByUser(String targetUserId, Pageable pageable) {
        if (userRepository.findById(targetUserId).isEmpty()) {
            LOGGER.warn("| ADMIN | auditoria por titular | usuário não encontrado | ID: {}", targetUserId);
            throw new DomainEntityNotFound(User.class, "ID", targetUserId);
        }
        Pageable clamped = clampPageSize(pageable);
        Page<AuditLog> logs = auditLogRepository.findByTargetUserId(targetUserId, clamped);
        return logs.map(AuditLogResponseDTO::toResponseDTO);
    }

    /** Feed geral de auditoria, paginado, sem filtro obrigatório de titular (AC-05). */
    public Page<AuditLogResponseDTO> getAllAuditLogs(Pageable pageable) {
        Pageable clamped = clampPageSize(pageable);
        Page<AuditLog> logs = auditLogRepository.findAll(clamped);
        return logs.map(AuditLogResponseDTO::toResponseDTO);
    }

    /**
     * Resultado interno de {@link #updateUserRoles}, não exposto via HTTP. Permite ao
     * controller (que detém o JWT do ator) decidir auditar {@code ROLE_GRANT}/{@code ROLE_REVOKE}
     * conforme a transição efetiva em ADMIN — nunca os dois, nenhum se não houve mudança.
     */
    public record RoleUpdateResult(AdminUserResponseDTO response, boolean adminGranted, boolean adminRevoked) {}

    /**
     * Atualiza as roles de um titular. Regras (ADR-014):
     * <ol>
     *   <li>404 se o titular não existir.</li>
     *   <li>400 ({@code IllegalArgumentException}) se {@code newRoles} contiver valor fora de
     *       {@code {USER, ADMIN}}, ou se {@code USER} estiver ausente (rejeição explícita, sem
     *       normalização silenciosa).</li>
     *   <li>409 ({@link SelfRoleRevocationException}) se o ator ({@code requestingAdminId}) for o
     *       próprio titular E o estado <b>persistido</b> tiver ADMIN E o payload remover ADMIN.</li>
     *   <li>Persiste e evicta os 3 caches ({@code usersById}, {@code usersByEmail},
     *       {@code authByEmail}) — roles afetam {@code AuthDTO}, consumido pelo authorization-server
     *       via Feign.</li>
     * </ol>
     */
    public RoleUpdateResult updateUserRoles(String userId, Set<String> newRoles, String requestingAdminId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> {
                LOGGER.warn("| ADMIN | atualização de roles | usuário não encontrado | ID: {}", userId);
                return new DomainEntityNotFound(User.class, "ID", userId);
            });

        validateRoles(newRoles);

        Set<String> previousRoles = user.getRoles() != null ? new HashSet<>(user.getRoles()) : new HashSet<>();
        boolean hadAdmin = previousRoles.contains(ROLE_ADMIN);
        boolean willHaveAdmin = newRoles.contains(ROLE_ADMIN);

        boolean isSelf = userId.equals(requestingAdminId);
        if (isSelf && hadAdmin && !willHaveAdmin) {
            LOGGER.warn("| ADMIN | auto-revogação bloqueada | ID: {}", userId);
            throw new SelfRoleRevocationException(userId);
        }

        user.setRoles(new HashSet<>(newRoles));
        User saved = userRepository.save(user);

        cacheService.evictById(userId);
        cacheService.evictByEmail(saved.getEmail());
        cacheService.evictByEmailAuth(saved.getEmail());
        // Revogação ativa (ADR-017): tokens já emitidos com as roles antigas são rejeitados em
        // ≈ segundos pelos resource servers, e o refresh é bloqueado no auth-server — força
        // re-login, onde as roles novas são re-derivadas.
        tokenRevocationService.revoke(userId);

        boolean adminGranted = !hadAdmin && willHaveAdmin;
        boolean adminRevoked = hadAdmin && !willHaveAdmin;

        LOGGER.info(
            "| ADMIN | roles atualizadas | ID: {}, roles: {}",
            userId,
            saved.getRoles()
        );

        return new RoleUpdateResult(AdminUserResponseDTO.toResponseDTO(saved), adminGranted, adminRevoked);
    }

    private void validateRoles(Set<String> newRoles) {
        if (newRoles == null || newRoles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        for (String role : newRoles) {
            if (!ALLOWED_ROLES.contains(role)) {
                throw new IllegalArgumentException("role not allowed: " + role);
            }
        }
        if (!newRoles.contains(ROLE_USER)) {
            throw new IllegalArgumentException("roles must contain USER");
        }
    }

    private Pageable clampPageSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_AUDIT_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_AUDIT_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }
}
