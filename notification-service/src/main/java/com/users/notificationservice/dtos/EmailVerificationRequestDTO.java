package com.users.notificationservice.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailVerificationRequestDTO {

    @NotBlank
    private String email;

    @NotBlank
    private String name;

    @NotBlank
    private String verificationLink;
}
