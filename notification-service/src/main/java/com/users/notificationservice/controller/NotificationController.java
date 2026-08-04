package com.users.notificationservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.users.notificationservice.dtos.EmailVerificationRequestDTO;
import com.users.notificationservice.services.EmailService;

import jakarta.validation.Valid;

// Canal interno (/internal/**), protegido por X-Internal-Token (InternalTokenFilter) e
// nunca exposto pelo gateway — só o user-service chama esta rota (ADR-006/ADR-015).
//
// SEM @Tag/@Operation, e sem springdoc no classpath (ADR-021): documentar este canal violaria a
// invariante do ADR-006. O serviço não publica OpenAPI algum — diferente do user-service, cujo
// /v3/api-docs é legítimo (o gateway o agrega) e onde o canal interno é escondido com @Hidden.
@RestController
@RequestMapping(value = "/internal/notifications")
public class NotificationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationController.class);
    private final EmailService emailService;

    public NotificationController(EmailService emailService) {
        this.emailService = emailService;
    }

    @PostMapping("/email-verification")
    public ResponseEntity<Void> sendEmailVerification(@RequestBody @Valid EmailVerificationRequestDTO request) {
        LOGGER.info("| POST | enviar verificação de e-mail");
        emailService.sendVerificationEmail(request);
        return ResponseEntity.ok().build();
    }
}
