package com.users.userservice.dtos;

import lombok.Getter;
import lombok.Setter;

// Espelha (sem compartilhar) o DTO de mesmo nome no notification-service — padrão já
// adotado no projeto: cada serviço possui seu próprio conjunto de DTOs por contrato.
@Getter
@Setter
public class EmailVerificationRequestDTO {

    private String email;
    private String name;
    private String verificationLink;

    public EmailVerificationRequestDTO() {
    }

    public EmailVerificationRequestDTO(String email, String name, String verificationLink) {
        this.email = email;
        this.name = name;
        this.verificationLink = verificationLink;
    }
}
