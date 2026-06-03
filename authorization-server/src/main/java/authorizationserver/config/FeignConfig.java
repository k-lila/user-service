package authorizationserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;

// Injeta o shared secret X-Internal-Token em toda chamada Feign ao user-service
// (canal interno /internal/**), casando com o InternalTokenFilter do user-service.
@Configuration
public class FeignConfig {

    @Value("${internal.api.token}")
    private String internalToken;

    @Bean
    public RequestInterceptor internalTokenInterceptor() {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
