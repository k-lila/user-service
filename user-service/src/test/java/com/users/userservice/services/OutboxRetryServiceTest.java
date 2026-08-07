package com.users.userservice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.domain.NotificationType;
import com.users.userservice.domain.User;
import com.users.userservice.repository.INotificationOutboxRepository;
import com.users.userservice.repository.IUserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRetryServiceTest {

    private static final Duration BACKOFF = Duration.ofMinutes(15);
    private static final Duration INTERVAL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;

    @Mock private INotificationOutboxRepository outboxRepository;
    @Mock private IUserRepository userRepository;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private OutboxRetryService service;

    @BeforeEach
    void setUp() {
        service = new OutboxRetryService(
                outboxRepository, userRepository, emailVerificationService,
                redis, BACKOFF, INTERVAL, MAX_ATTEMPTS);
        when(redis.opsForValue()).thenReturn(valueOps);
        lockAcquired(true);
    }

    private void lockAcquired(boolean acquired) {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(acquired);
    }

    /** Outbox velho o bastante para o backoff já ter passado. */
    private NotificationOutbox outbox(String id, String userId, NotificationStatus status) {
        NotificationOutbox o = new NotificationOutbox();
        o.setId(id);
        o.setUserId(userId);
        o.setType(NotificationType.EMAIL_VERIFICATION);
        o.setStatus(status);
        o.setCreatedAt(Instant.now().minus(Duration.ofHours(1)));
        o.setLastAttemptAt(Instant.now().minus(Duration.ofHours(1)));
        return o;
    }

    private User user(String id, Boolean emailVerified) {
        User u = new User();
        u.setId(id);
        u.setEmail("fulano@email.com");
        u.setName("Fulano");
        u.setEmailVerified(emailVerified);
        return u;
    }

    private void candidates(NotificationOutbox... items) {
        when(outboxRepository.findTop100ByTypeAndStatusInOrderByCreatedAtAsc(
                eq(NotificationType.EMAIL_VERIFICATION), anyList()))
                .thenReturn(List.of(items));
    }

    @Test
    void deveReemitirTokenNovo_quandoOutboxFalhouEUsuarioSegueNaoVerificado() {
        NotificationOutbox o = outbox("out-1", "user-1", NotificationStatus.FAILED);
        User u = user("user-1", false);
        candidates(o);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(u));
        when(outboxRepository.countByUserIdAndType("user-1", NotificationType.EMAIL_VERIFICATION))
                .thenReturn(1L);

        service.retryPendingNotifications();

        // O token em claro não é persistido: o retry NÃO reenvia o mesmo e-mail, emite outro.
        verify(emailVerificationService).issueVerificationEmail(u);

        ArgumentCaptor<NotificationOutbox> saved = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(saved.capture());
        assertEquals(NotificationStatus.SUPERSEDED, saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
    }

    /**
     * O registro tem de ser marcado {@code SUPERSEDED} <b>antes</b> de o novo ser emitido, para
     * não sobrar dois registros ativos para o mesmo titular se a emissão falhar em seguida.
     */
    @Test
    void deveMarcarSupersedidoAntesDeEmitirONovo() {
        NotificationOutbox o = outbox("out-1", "user-1", NotificationStatus.FAILED);
        User u = user("user-1", false);
        candidates(o);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(u));
        when(outboxRepository.countByUserIdAndType("user-1", NotificationType.EMAIL_VERIFICATION))
                .thenReturn(1L);

        InOrder inOrder = inOrder(outboxRepository, emailVerificationService);

        service.retryPendingNotifications();

        inOrder.verify(outboxRepository).save(any());
        inOrder.verify(emailVerificationService).issueVerificationEmail(u);
    }

    @Test
    void naoDeveReemitir_quandoUsuarioJaConfirmouPorOutroLink() {
        NotificationOutbox o = outbox("out-1", "user-1", NotificationStatus.FAILED);
        candidates(o);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user("user-1", true)));

        service.retryPendingNotifications();

        verify(emailVerificationService, never()).issueVerificationEmail(any());
        // Ainda assim encerra o registro, para não voltar como candidato todo ciclo.
        verify(outboxRepository).save(any());
    }

    @Test
    void naoDeveReemitir_quandoEmailVerifiedNuloPorSerLegado() {
        NotificationOutbox o = outbox("out-1", "user-1", NotificationStatus.FAILED);
        candidates(o);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user("user-1", null)));

        service.retryPendingNotifications();

        verify(emailVerificationService, never()).issueVerificationEmail(any());
    }

    @Test
    void naoDeveReemitir_quandoBackoffAindaNaoPassou() {
        NotificationOutbox o = outbox("out-1", "user-1", NotificationStatus.PENDING);
        // Tentativa recente: dentro da janela o PENDING ainda pode estar em voo no executor
        // assíncrono — reemitir agora duplicaria um e-mail que estava para dar certo.
        o.setLastAttemptAt(Instant.now().minus(Duration.ofMinutes(1)));
        candidates(o);

        service.retryPendingNotifications();

        verify(emailVerificationService, never()).issueVerificationEmail(any());
        verify(outboxRepository, never()).save(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void deveDesistir_quandoTetoDeTentativasAtingido() {
        NotificationOutbox o = outbox("out-1", "user-1", NotificationStatus.FAILED);
        candidates(o);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user("user-1", false)));
        when(outboxRepository.countByUserIdAndType("user-1", NotificationType.EMAIL_VERIFICATION))
                .thenReturn((long) MAX_ATTEMPTS);

        service.retryPendingNotifications();

        verify(emailVerificationService, never()).issueVerificationEmail(any());
        verify(outboxRepository).save(any());
    }

    @Test
    void deveEncerrarRegistro_quandoTitularNaoExisteMais() {
        NotificationOutbox o = outbox("out-1", "user-sumido", NotificationStatus.FAILED);
        candidates(o);
        when(userRepository.findById("user-sumido")).thenReturn(Optional.empty());

        service.retryPendingNotifications();

        verify(emailVerificationService, never()).issueVerificationEmail(any());
        verify(outboxRepository).save(any());
    }

    @Test
    void naoDeveVarrer_quandoOutraInstanciaJaSeguraOLock() {
        lockAcquired(false);

        service.retryPendingNotifications();

        verifyNoInteractions(outboxRepository, userRepository, emailVerificationService);
    }

    /**
     * Fail-CLOSED, ao contrário do resto do sistema (cache, rate limit e revogação são fail-open
     * de propósito). Sem lock confirmado a varredura não roda: falhar aberto com N instâncias
     * significaria N e-mails duplicados por ciclo, e pular um ciclo não custa nada.
     */
    @Test
    void naoDeveVarrer_quandoRedisIndisponivel() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis fora"));

        service.retryPendingNotifications();

        verifyNoInteractions(outboxRepository, userRepository, emailVerificationService);
    }
}
