package com.users.userservice.dtos;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRequestDTO {

    /** Grupo de validação do registro — senha obrigatória só na criação. */
    public interface OnCreate {}

    @NotBlank
    @Size(min = 1, max = 50)
    String name;

    @NotBlank
    @Email
    String email;

    // No update a senha é opcional (null = mantém a atual); @Size/@Pattern pulam null.
    @NotBlank(groups = OnCreate.class)
    @Size(min = 8, max = 72)
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "deve conter ao menos uma letra e um número")
    String password;

    // Consentimento LGPD (base legal): obrigatório e true SÓ no cadastro (grupo OnCreate).
    // @AssertTrue trata null como válido → @NotNull cobre a ausência; juntos exigem presente E true.
    // Ignorado no update (não pertence ao grupo Default).
    @NotNull(groups = OnCreate.class)
    @AssertTrue(groups = OnCreate.class, message = "é necessário aceitar os termos e a política de privacidade")
    Boolean termsAccepted;
}
