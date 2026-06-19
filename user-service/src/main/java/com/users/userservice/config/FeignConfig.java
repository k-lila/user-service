package com.users.userservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;

// Injeta o shared secret X-Internal-Token em toda chamada Feign ao notification-service
// (canal interno /internal/**, ADR-015) — mesmo padrão do FeignConfig do authorization-server
// (ADR-006), só que aqui o user-service é o consumidor (inversão do papel usual).
@Configuration
public class FeignConfig {

    @Value("${internal.api.token}")
    private String internalToken;

    @Bean
    public RequestInterceptor internalTokenInterceptor() {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
