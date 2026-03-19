package authenticationserver.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequestDTO {
    String name;
    String email;
    String passwordHash;
}
