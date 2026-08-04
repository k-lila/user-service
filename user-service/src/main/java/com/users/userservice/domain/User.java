package com.users.userservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Document(collection = "users")
@Getter
@Setter
public class User {
    @Id
    private String id;

    @NotNull
    @Size(min = 1, max = 50)
    @Schema(description = "Nome", minLength = 1, maxLength = 50, nullable = false)
    private String name;

    @NotNull
    @Indexed(unique = true)
    @Schema(description = "E-mail", nullable = false)
    @Pattern(regexp = ".+@.+\\..+", message = "E-mail inválido")
    private String email;

    @NotNull
    @Size(min = 8, max = 100)
    @Schema(description = "Senha hash", minLength = 8, maxLength = 100, nullable = false)
    private String passwordHash;

    @NotNull
    @Schema(description = "Data de registro", nullable = false)
    private Instant registrationDate;

    @NotNull
    @Schema(description = "Lista de papéis do usuário", nullable = false)
    private Set<String> roles;

    @NotNull
    @Schema(description = "Status | ativo ou inativo", nullable = false)
    private Boolean active;

    // Consentimento LGPD registrado no cadastro. Nullable no entity (sem @NotNull) para não
    // quebrar o re-save de usuários legados anteriores ao campo; em novos cadastros é sempre setado.
    @Schema(description = "Momento do aceite dos termos/privacidade (LGPD)", nullable = true)
    private Instant consentAcceptedAt;

    @Schema(description = "Versão dos termos/privacidade aceita no cadastro", nullable = true)
    private String termsVersion;

    @Schema(description = "IDs dos tenants aos quais o usuário pertence", nullable = true)
    private List<String> tenantIds;

    // Nullable: null (legado, anterior ao campo) é tratado como verificado (true) em toda a
    // leitura, igual ao padrão do consentAcceptedAt legado. O campo NÃO é mais scaffold — o
    // fluxo de verificação existe desde o ADR-015 (EmailVerificationService) e o alimenta de
    // fato no cadastro (false) e na confirmação (true + emailVerifiedAt).
    @Schema(description = "Indica se o e-mail foi verificado", nullable = true)
    private Boolean emailVerified;

    @Schema(description = "Momento em que o e-mail foi verificado", nullable = true)
    private Instant emailVerifiedAt;
}
