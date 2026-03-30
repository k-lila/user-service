package authenticationserver.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InternalRegisterRequestDTO {
    String name;
    String email;
    String passwordHash;
}
