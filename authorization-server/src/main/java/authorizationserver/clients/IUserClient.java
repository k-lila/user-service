package authorizationserver.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import authorizationserver.dtos.AuthDTO;

@FeignClient(name = "user-service")
public interface IUserClient {
    @GetMapping("/internal/users/email/{email}")
    AuthDTO getUserByEmail(@PathVariable("email") String email);
}
