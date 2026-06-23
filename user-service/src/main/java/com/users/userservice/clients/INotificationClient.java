package com.users.userservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.users.userservice.dtos.EmailVerificationRequestDTO;

// Inversão do padrão usual do projeto: aqui o user-service é CONSUMIDOR Feign do
// notification-service (em vez de provedor). Mesmo padrão de resiliência do auth-server
// (Resilience4j circuit breaker + fallback factory, ADR-004/ADR-015).
@FeignClient(name = "notification-service", fallbackFactory = NotificationClientFallbackFactory.class)
public interface INotificationClient {

    @PostMapping("/internal/notifications/email-verification")
    void sendEmailVerification(@RequestBody EmailVerificationRequestDTO request);
}
