package com.users.userservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.users.userservice.domain.AuditAction;
import com.users.userservice.dtos.ResendVerificationRequestDTO;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.services.AuditService;
import com.users.userservice.services.EmailVerificationService;
import com.users.userservice.services.RegisterService;
import com.users.userservice.services.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;

@Tag(
    name = "Controller",
    description = "Endpoints do serviço de usuários"
)
@RestController
@RequestMapping(value = "/v1/users")
public class UserController {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserController.class);
    private final RegisterService registerService;
    private final SearchService searchService;
    private final AuditService auditService;
    private final EmailVerificationService emailVerificationService;

    public UserController(
            RegisterService registerService,
            SearchService searchService,
            AuditService auditService,
            EmailVerificationService emailVerificationService) {
        this.registerService = registerService;
        this.searchService = searchService;
        this.auditService = auditService;
        this.emailVerificationService = emailVerificationService;
    }

    @Operation(summary = "Registrar novo usuário")
    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> registerUser(
            @RequestBody @Validated({Default.class, UserRequestDTO.OnCreate.class}) UserRequestDTO userDTO) {
        LOGGER.info(
            "| POST | registrar usuário | nome: {}",
            userDTO.getName()
        );
        UserResponseDTO newUser = registerService.registerUser(userDTO);
        auditService.recordRegistration(newUser.getId(), newUser.getEmail());
        return ResponseEntity.status(201).body(newUser);
    }

    // Endpoint público, pré-sessão (usuário recém-cadastrado, sem cookie/JWT). Token opaco
    // de alta entropia + TTL 15min mitigam o transporte via query string (ADR-015).
    @Operation(summary = "Confirmar verificação de e-mail")
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        LOGGER.info("| GET | confirmar verificação de e-mail");
        emailVerificationService.confirm(token);
        return ResponseEntity.ok().build();
    }

    // Resposta sempre 202 idêntica, independente do branch interno (anti-enumeração, AC-10) —
    // não revela se o e-mail existe, já está verificado, ou está pendente.
    @Operation(summary = "Reenviar e-mail de verificação")
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody @Valid ResendVerificationRequestDTO dto) {
        LOGGER.info("| POST | reenviar verificação de e-mail");
        emailVerificationService.resend(dto.getEmail());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Lista todos os usuários")
    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Page<UserResponseDTO>> searchAll(Pageable pageable) {
        LOGGER.info(
            "| GET | buscar todos | página: {}x{}",
            pageable.getPageSize(),
            pageable.getPageNumber()
        );
        Page<UserResponseDTO> users = searchService.searchAll(pageable);
        return ResponseEntity.ok(users);
    }

    @Operation(summary = "Buscar usuário por ID")
    @GetMapping(value = "/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponseDTO> searchById(
            @PathVariable(value = "id", required = true) String userID,
            @AuthenticationPrincipal Jwt jwt) {
        LOGGER.info(
            "| GET | buscar por ID | ID: {}",
            userID
        );
        UserResponseDTO found = searchService.searchById(userID);
        auditCrossSubjectRead(jwt, found);
        return ResponseEntity.ok(found);
    }

    @Operation(summary = "Buscar usuário por Email")
    @GetMapping(value = "/email/{email}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponseDTO> searchByEmail(
            @PathVariable(value = "email", required = true) String email,
            @AuthenticationPrincipal Jwt jwt) {
        LOGGER.info("| GET | buscar por email");

        UserResponseDTO found = searchService.searchByEmail(email);
        auditCrossSubjectRead(jwt, found);
        return ResponseEntity.ok(found);
    }

    @Operation(summary = "Modificar um usuário")
    @PutMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponseDTO> updateUser(@RequestBody @Valid UserRequestDTO userDTO, @AuthenticationPrincipal Jwt jwt) {
        String userID = jwt.getClaim("userID");
        LOGGER.info(
            "| PUT | atualizar | nome: {}, ID: {}",
            userDTO.getName(),
            userID
        );
        UserResponseDTO updated = registerService.updateUser(userDTO, userID);
        auditService.recordFromJwt(AuditAction.UPDATE, jwt, userID, updated.getEmail());
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Desativar usuário atual")
    @DeleteMapping(value = "/remove/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> removeCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String userID = jwt.getClaim("userID");
        LOGGER.info(
            "| DELETE | auto-remoção | ID: {}",
            userID
        );
        registerService.deactivateUser(userID);
        auditService.recordFromJwt(AuditAction.SOFT_DELETE_SELF, jwt, userID, null);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Deletar definitivamente o usuário atual")
    @DeleteMapping(value = "/delete/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> deleteCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String userID = jwt.getClaim("userID");
        LOGGER.info(
            "| DELETE | auto hard-delete | ID: {}",
            userID
        );
        registerService.deleteUser(userID);
        auditService.recordFromJwt(AuditAction.HARD_DELETE_SELF, jwt, userID, null);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Retornar o usuário atual")
    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponseDTO> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String userID = jwt.getClaim("userID");
        LOGGER.info("| GET | usuário autenticado | ID: {}", userID);
        UserResponseDTO user = searchService.searchById(userID);
        return ResponseEntity.ok(user);
    }

    /**
     * Audita a leitura apenas quando o titular consultado difere do solicitante
     * (READ_CROSS_SUBJECT). Leitura do próprio dado (ex.: via /me ou consulta ao próprio ID)
     * não gera trilha — baixo valor e alto volume.
     */
    private void auditCrossSubjectRead(Jwt jwt, UserResponseDTO found) {
        String callerId = jwt != null ? jwt.getClaimAsString("userID") : null;
        if (found != null && found.getId() != null && !found.getId().equals(callerId)) {
            auditService.recordFromJwt(AuditAction.READ_CROSS_SUBJECT, jwt, found.getId(), found.getEmail());
        }
    }
}
