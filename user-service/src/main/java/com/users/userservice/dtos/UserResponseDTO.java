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
    String consentAcceptedAt;
    String termsVersion;

    public static UserResponseDTO toResponseDTO(User user) {
        UserResponseDTO userResponseDTO = new UserResponseDTO();
        userResponseDTO.setId(user.getId());
        userResponseDTO.setName(user.getName());
        userResponseDTO.setEmail(user.getEmail());
        userResponseDTO.setRegistrationDate(user.getRegistrationDate().toString());
        userResponseDTO.setActive(user.getActive());
        // Consentimento LGPD (nullable para usuários legados anteriores ao campo).
        if (user.getConsentAcceptedAt() != null) {
            userResponseDTO.setConsentAcceptedAt(user.getConsentAcceptedAt().toString());
        }
        userResponseDTO.setTermsVersion(user.getTermsVersion());
        return userResponseDTO;
    }

}
