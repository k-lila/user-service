package com.users.userservice.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.users.userservice.clients.INotificationClient;
import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.dtos.EmailVerificationRequestDTO;
import com.users.userservice.repository.INotificationOutboxRepository;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock private INotificationClient notificationClient;
    @Mock private INotificationOutboxRepository outboxRepository;

    private NotificationDispatchService service;

    @BeforeEach
    void setUp() {
        service = new NotificationDispatchService(notificationClient, outboxRepository);
    }

    private NotificationOutbox buildOutbox(String id) {
        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(id);
        outbox.setStatus(NotificationStatus.PENDING);
        outbox.setAttempts(0);
        return outbox;
    }

    @Test
    void dispatchEmailVerification_deveMarcarOutboxComoSent_quandoFeignTemSucesso() {
        NotificationOutbox outbox = buildOutbox("outbox-1");
        EmailVerificationRequestDTO request =
                new EmailVerificationRequestDTO("fulano@email.com", "Fulano", "http://link");

        when(outboxRepository.findById("outbox-1")).thenReturn(Optional.of(outbox));

        service.dispatchEmailVerification("outbox-1", request);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(captor.capture());
        NotificationOutbox salvo = captor.getValue();
        assertThat(salvo.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(salvo.getAttempts()).isEqualTo(1);
        assertThat(salvo.getLastAttemptAt()).isNotNull();
    }

    @Test
    void dispatchEmailVerification_deveMarcarOutboxComoFailed_quandoFeignLancaExcecao() {
        NotificationOutbox outbox = buildOutbox("outbox-2");
        EmailVerificationRequestDTO request =
                new EmailVerificationRequestDTO("fulano@email.com", "Fulano", "http://link");

        when(outboxRepository.findById("outbox-2")).thenReturn(Optional.of(outbox));
        org.mockito.Mockito.doThrow(new RuntimeException("notification-service indisponível"))
                .when(notificationClient).sendEmailVerification(any());

        service.dispatchEmailVerification("outbox-2", request);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void dispatchEmailVerification_naoDevePropagarExcecao_quandoFeignFalha() {
        NotificationOutbox outbox = buildOutbox("outbox-3");
        EmailVerificationRequestDTO request =
                new EmailVerificationRequestDTO("fulano@email.com", "Fulano", "http://link");

        when(outboxRepository.findById("outbox-3")).thenReturn(Optional.of(outbox));
        org.mockito.Mockito.doThrow(new RuntimeException("circuit breaker aberto"))
                .when(notificationClient).sendEmailVerification(any());

        assertThatCode(() -> service.dispatchEmailVerification("outbox-3", request))
                .doesNotThrowAnyException();
    }
}
