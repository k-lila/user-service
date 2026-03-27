package authenticationserver.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterResponseDTO {
    String id;
    String email;
    String token;
}
