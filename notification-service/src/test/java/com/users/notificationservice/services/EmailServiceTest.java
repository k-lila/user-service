package com.users.notificationservice.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.users.notificationservice.dtos.EmailVerificationRequestDTO;
import com.users.notificationservice.exceptions.EmailSendFailedException;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private JavaMailSender mailSender;

    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService(mailSender, "no-reply@users.local");
    }

    private EmailVerificationRequestDTO buildRequest() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        dto.setEmail("fulano@email.com");
        dto.setName("Fulano");
        dto.setVerificationLink("http://localhost:8081/v1/users/verify-email?token=abc123");
        return dto;
    }

    @Test
    void sendVerificationEmail_deveEnviarMensagem_comDestinatarioAssuntoELinkNoCorpo() {
        EmailVerificationRequestDTO request = buildRequest();

        service.sendVerificationEmail(request);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("fulano@email.com");
        assertThat(sent.getFrom()).isEqualTo("no-reply@users.local");
        assertThat(sent.getSubject()).isNotBlank();
        assertThat(sent.getText()).contains(request.getVerificationLink());
        assertThat(sent.getText()).contains("Fulano");
    }

    @Test
    void sendVerificationEmail_deveLancarEmailSendFailedException_quandoMailSenderFalha() {
        EmailVerificationRequestDTO request = buildRequest();
        doThrow(new MailSendException("smtp indisponível")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThrows(EmailSendFailedException.class, () -> service.sendVerificationEmail(request));
    }
}
