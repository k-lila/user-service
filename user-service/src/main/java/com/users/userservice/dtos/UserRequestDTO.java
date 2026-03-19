package com.users.userservice.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRequestDTO {
    String name;
    String email;
    String passwordHash;
}
