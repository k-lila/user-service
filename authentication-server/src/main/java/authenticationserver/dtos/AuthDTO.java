package authenticationserver.dtos;

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
}
