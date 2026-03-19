package com.users.userservice.dtos;

import com.users.userservice.domain.User;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserResponseDTO {
    String id;
    String name;
    String email;
    String registrationDate;
    Boolean active;

    public static UserResponseDTO toResponseDTO(User user) {
        UserResponseDTO userResponseDTO = new UserResponseDTO();
        userResponseDTO.setId(user.getId());
        userResponseDTO.setName(user.getName());
        userResponseDTO.setEmail(user.getEmail());
        userResponseDTO.setRegistrationDate(user.getRegistrationDate().toString());
        userResponseDTO.setActive(user.getActive());
        return userResponseDTO;
    }

}
