package com.users.notificationservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.users.notificationservice.dtos.EmailVerificationRequestDTO;
import com.users.notificationservice.exceptions.EmailSendFailedException;
import com.users.notificationservice.exceptions.GlobalExceptionHandler;
import com.users.notificationservice.services.EmailService;

/**
 * Slice de controller (sem Spring Security neste serviço — só {@code GlobalExceptionHandler}
 * é relevante aqui). O enforcement do {@code X-Internal-Token} é coberto isoladamente em
 * {@code InternalTokenFilterTest} (o {@code FilterRegistrationBean} não é carregado por
 * {@code @WebMvcTest}, que não sobe o contexto de filtros de servlet completo).
 */
@WebMvcTest(NotificationController.class)
@Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private EmailService emailService;

    private EmailVerificationRequestDTO buildRequest() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        dto.setEmail("fulano@email.com");
        dto.setName("Fulano");
        dto.setVerificationLink("http://localhost:8081/v1/users/verify-email?token=abc");
        return dto;
    }

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    @Test
    void sendEmailVerification_deveRetornar200_quandoPayloadValidoESemFalha() throws Exception {
        mockMvc.perform(post("/internal/notifications/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void sendEmailVerification_deveRetornar400_quandoEmailAusente() throws Exception {
        EmailVerificationRequestDTO dto = buildRequest();
        dto.setEmail(null);

        mockMvc.perform(post("/internal/notifications/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendEmailVerification_deveRetornar400_quandoNomeAusente() throws Exception {
        EmailVerificationRequestDTO dto = buildRequest();
        dto.setName(null);

        mockMvc.perform(post("/internal/notifications/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendEmailVerification_deveRetornar400_quandoVerificationLinkAusente() throws Exception {
        EmailVerificationRequestDTO dto = buildRequest();
        dto.setVerificationLink(null);

        mockMvc.perform(post("/internal/notifications/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendEmailVerification_deveRetornar502_quandoEmailServiceLancaEmailSendFailedException() throws Exception {
        doThrow(new EmailSendFailedException("falha SMTP", new RuntimeException("timeout")))
                .when(emailService).sendVerificationEmail(any());

        mockMvc.perform(post("/internal/notifications/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(buildRequest())))
                .andExpect(status().isBadGateway());
    }
}
