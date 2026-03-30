package authenticationserver.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequestDTO {
    @NotBlank
    @Size(min = 3)
    String name;

    @NotBlank
    @Email
    String email;

    @NotBlank
    @Size(min = 3)
    String rawPassword;
}
