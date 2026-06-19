package authorizationserver.dtos;

import java.time.Instant;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthDTO {
    String id;
    String email;
    String passwordHash;
    Boolean active;
    Set<String> roles;
    Boolean emailVerified;
    // Aditivo (ADR-015): usado por AuthorizationService para calcular a janela de carência
    // (grace period) do gate de verificação de e-mail.
    Instant registrationDate;
}
