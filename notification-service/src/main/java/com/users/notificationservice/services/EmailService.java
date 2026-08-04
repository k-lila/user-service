package com.users.notificationservice.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.users.notificationservice.dtos.EmailVerificationRequestDTO;
import com.users.notificationservice.exceptions.EmailSendFailedException;

@Service
public class EmailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailService(JavaMailSender mailSender, @Value("${app.mail.from:no-reply@users.local}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void sendVerificationEmail(EmailVerificationRequestDTO request) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(request.getEmail());
        message.setSubject("Confirme seu e-mail");
        message.setText(
            "Olá " + request.getName() + ",\n\n"
            // Sem prazo numérico de propósito: o TTL real é app.email-verification.token-ttl no
            // user-service (configurável por env) e este serviço não o conhece — o DTO não o
            // carrega. Cravar "15 minutos" aqui faz o e-mail mentir assim que a env mudar.
            // Carregar o TTL no DTO seria mudança de contrato Feign, com ADR próprio.
            + "Confirme seu cadastro acessando o link abaixo (o link expira em pouco tempo):\n"
            + request.getVerificationLink() + "\n\n"
            + "Se você não se cadastrou, ignore este e-mail."
        );
        try {
            mailSender.send(message);
            LOGGER.info("| EMAIL | enviado | tipo: EMAIL_VERIFICATION");
        } catch (MailException e) {
            LOGGER.warn("| EMAIL | falha no envio | tipo: EMAIL_VERIFICATION | cause: {}", e.getMessage());
            throw new EmailSendFailedException("Falha ao enviar e-mail de verificação", e);
        }
    }
}
