package authenticationserver.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import authenticationserver.dtos.AuthDTO;
import authenticationserver.dtos.InternalRegisterRequestDTO;
import authenticationserver.dtos.RegisterResponseDTO;

@FeignClient(name = "user-service")
public interface IUserClient {
    @GetMapping("/internal/users/email/{email}")
    AuthDTO getUserByEmail(@PathVariable("email") String email);

    @PostMapping("/internal/users")
    RegisterResponseDTO registerUser(@RequestBody InternalRegisterRequestDTO registerRequest);
}
