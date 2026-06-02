package com.users.userservice.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRequestDTO {

    @NotBlank
    @Size(min = 1, max = 50)
    String name;

    @NotBlank
    @Email
    String email;

    @Size(min = 8)
    String password;
}
