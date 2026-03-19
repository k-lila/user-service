package com.users.userservice.domain;

import java.time.Instant;
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
}
