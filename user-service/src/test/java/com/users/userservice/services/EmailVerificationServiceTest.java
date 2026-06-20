package com.users.userservice.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.domain.NotificationType;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.EmailVerificationRequestDTO;
import com.users.userservice.exceptions.DomainEntityNotFound;
import com.users.userservice.exceptions.InvalidVerificationTokenException;
import com.users.userservice.repository.INotificationOutboxRepository;
import com.users.userservice.repository.IUserRepository;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock private IUserRepository userRepository;
    @Mock private INotificationOutboxRepository outboxRepository;
    @Mock private VerificationTokenService tokenService;
    @Mock private NotificationDispatchService dispatchService;
    @Mock private ResendRateLimitService resendRateLimitService;
    @Mock private CacheService cacheService;
    @Mock private AuditService auditService;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(
                userRepository,
                outboxRepository,
                tokenService,
                dispatchService,
                resendRateLimitService,
                cacheService,
                auditService,
                "http://localhost:8081",
                Duration.ofDays(30));
    }

    private User buildUser(String id, String email, Boolean emailVerified) {
        User u = new User();
        u.setId(id);
        u.setName("Fulano");
        u.setEmail(email);
        u.setEmailVerified(emailVerified);
        return u;
    }

    private NotificationOutbox buildOutbox(String id, String userId, NotificationStatus status, Instant expiresAt) {
        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(id);
        outbox.setUserId(userId);
        outbox.setType(NotificationType.EMAIL_VERIFICATION);
        outbox.setTokenHash("hash-" + id);
        outbox.setStatus(status);
        outbox.setExpiresAt(expiresAt);
        return outbox;
    }

    // ── issueVerificationEmail ───────────────────────────────────────────────

    @Test
    void issueVerificationEmail_deveCriarOutboxPendente_comTokenHashEPurgeAtMaiorQueExpiresAt() {
        User user = buildUser("user-1", "fulano@email.com", false);
        when(tokenService.generateToken()).thenReturn("token-claro");
        when(tokenService.hashToken("token-claro")).thenReturn("hash-do-token");
        when(tokenService.getTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(outboxRepository.save(any(NotificationOutbox.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.issueVerificationEmail(user);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(captor.capture());
        NotificationOutbox outbox = captor.getValue();
        assertThat(outbox.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(outbox.getTokenHash()).isEqualTo("hash-do-token");
        assertThat(outbox.getPurgeAt()).isAfter(outbox.getExpiresAt());
    }

    @Test
    void issueVerificationEmail_deveChamarDispatch_comOutboxIdEDtoCorretos() {
        User user = buildUser("user-1", "fulano@email.com", false);
        when(tokenService.generateToken()).thenReturn("token-claro");
        when(tokenService.hashToken("token-claro")).thenReturn("hash-do-token");
        when(tokenService.getTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(outboxRepository.save(any(NotificationOutbox.class)))
                .thenAnswer(invocation -> {
                    NotificationOutbox o = invocation.getArgument(0);
                    o.setId("outbox-gerado");
                    return o;
                });

        service.issueVerificationEmail(user);

        ArgumentCaptor<EmailVerificationRequestDTO> dtoCaptor =
                ArgumentCaptor.forClass(EmailVerificationRequestDTO.class);
        verify(dispatchService).dispatchEmailVerification(eq("outbox-gerado"), dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getEmail()).isEqualTo("fulano@email.com");
        assertThat(dtoCaptor.getValue().getName()).isEqualTo("Fulano");
        assertThat(dtoCaptor.getValue().getVerificationLink()).contains("token-claro");
    }

    // ── confirm ───────────────────────────────────────────────────────────────

    @Test
    void confirm_deveVerificarUsuario_quandoTokenValidoEPendente() {
        User user = buildUser("user-1", "fulano@email.com", false);
        NotificationOutbox outbox = buildOutbox("outbox-1", "user-1", NotificationStatus.PENDING,
                Instant.now().plusSeconds(600));

        when(tokenService.hashToken("token-valido")).thenReturn("hash-1");
        when(outboxRepository.findByTokenHash("hash-1")).thenReturn(Optional.of(outbox));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.confirm("token-valido");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmailVerified()).isTrue();
        assertThat(userCaptor.getValue().getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void confirm_deveEvictarOsTresCaches_quandoTokenValido() {
        User user = buildUser("user-1", "fulano@email.com", false);
        NotificationOutbox outbox = buildOutbox("outbox-1", "user-1", NotificationStatus.SENT,
                Instant.now().plusSeconds(600));

        when(tokenService.hashToken("token-valido")).thenReturn("hash-1");
        when(outboxRepository.findByTokenHash("hash-1")).thenReturn(Optional.of(outbox));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.confirm("token-valido");

        verify(cacheService).evictById("user-1");
        verify(cacheService).evictByEmail("fulano@email.com");
        verify(cacheService).evictByEmailAuth("fulano@email.com");
    }

    @Test
    void confirm_deveMarcarOutboxComoConfirmed_eAuditarEmailVerified() {
        User user = buildUser("user-1", "fulano@email.com", false);
        NotificationOutbox outbox = buildOutbox("outbox-1", "user-1", NotificationStatus.PENDING,
                Instant.now().plusSeconds(600));

        when(tokenService.hashToken("token-valido")).thenReturn("hash-1");
        when(outboxRepository.findByTokenHash("hash-1")).thenReturn(Optional.of(outbox));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.confirm("token-valido");

        ArgumentCaptor<NotificationOutbox> outboxCaptor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.CONFIRMED);
        verify(auditService).recordEmailVerified("user-1", "fulano@email.com");
    }

    @Test
    void confirm_deveLancarInvalidVerificationToken_quandoTokenInexistente() {
        when(tokenService.hashToken("token-inexistente")).thenReturn("hash-inexistente");
        when(outboxRepository.findByTokenHash("hash-inexistente")).thenReturn(Optional.empty());

        assertThrows(InvalidVerificationTokenException.class, () -> service.confirm("token-inexistente"));
    }

    @Test
    void confirm_deveLancarInvalidVerificationToken_quandoTokenExpirado() {
        NotificationOutbox outbox = buildOutbox("outbox-1", "user-1", NotificationStatus.PENDING,
                Instant.now().minusSeconds(60));

        when(tokenService.hashToken("token-expirado")).thenReturn("hash-1");
        when(outboxRepository.findByTokenHash("hash-1")).thenReturn(Optional.of(outbox));

        assertThrows(InvalidVerificationTokenException.class, () -> service.confirm("token-expirado"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void confirm_naoDeveLancar_quandoOutboxJaConfirmado() {
        NotificationOutbox outbox = buildOutbox("outbox-1", "user-1", NotificationStatus.CONFIRMED,
                Instant.now().plusSeconds(600));

        when(tokenService.hashToken("token-usado")).thenReturn("hash-1");
        when(outboxRepository.findByTokenHash("hash-1")).thenReturn(Optional.of(outbox));

        service.confirm("token-usado");

        verify(userRepository, never()).findById(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void confirm_deveLancarInvalidVerificationToken_quandoOutboxSuperseded() {
        NotificationOutbox outbox = buildOutbox("outbox-1", "user-1", NotificationStatus.SUPERSEDED,
                Instant.now().plusSeconds(600));

        when(tokenService.hashToken("token-superseded")).thenReturn("hash-1");
        when(outboxRepository.findByTokenHash("hash-1")).thenReturn(Optional.of(outbox));

        assertThrows(InvalidVerificationTokenException.class, () -> service.confirm("token-superseded"));
    }

    // ── resend ────────────────────────────────────────────────────────────────

    @Test
    void resend_naoDeveFazerNada_quandoEmailInexistente() {
        when(userRepository.findByEmail("naoexiste@email.com")).thenReturn(Optional.empty());

        service.resend("naoexiste@email.com");

        verify(outboxRepository, never()).findByUserIdAndTypeAndStatusIn(any(), any(), any());
        verify(dispatchService, never()).dispatchEmailVerification(any(), any());
    }

    @Test
    void resend_naoDeveFazerNada_quandoEmailJaVerificado() {
        User user = buildUser("user-1", "fulano@email.com", true);
        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.of(user));

        service.resend("fulano@email.com");

        verify(resendRateLimitService, never()).isThrottled(anyString());
        verify(dispatchService, never()).dispatchEmailVerification(any(), any());
    }

    @Test
    void resend_naoDeveFazerNada_quandoEmailVerifiedNuloLegado() {
        User user = buildUser("user-1", "fulano@email.com", null);
        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.of(user));

        service.resend("fulano@email.com");

        verify(resendRateLimitService, never()).isThrottled(anyString());
        verify(dispatchService, never()).dispatchEmailVerification(any(), any());
    }

    @Test
    void resend_naoDeveChamarRecordResend_quandoThrottled() {
        User user = buildUser("user-1", "fulano@email.com", false);
        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.of(user));
        when(resendRateLimitService.isThrottled("fulano@email.com")).thenReturn(true);

        service.resend("fulano@email.com");

        verify(resendRateLimitService, never()).recordResend(anyString());
        verify(dispatchService, never()).dispatchEmailVerification(any(), any());
    }

    @Test
    void resend_deveChamarRecordResendESupersederAnteriores_quandoNaoThrottled() {
        User user = buildUser("user-1", "fulano@email.com", false);
        NotificationOutbox anterior = buildOutbox("outbox-anterior", "user-1", NotificationStatus.PENDING,
                Instant.now().plusSeconds(300));

        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.of(user));
        when(resendRateLimitService.isThrottled("fulano@email.com")).thenReturn(false);
        when(outboxRepository.findByUserIdAndTypeAndStatusIn(
                "user-1", NotificationType.EMAIL_VERIFICATION,
                List.of(NotificationStatus.PENDING, NotificationStatus.SENT)))
                .thenReturn(List.of(anterior));
        when(tokenService.generateToken()).thenReturn("token-novo");
        when(tokenService.hashToken("token-novo")).thenReturn("hash-novo");
        when(tokenService.getTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(outboxRepository.save(any(NotificationOutbox.class)))
                .thenAnswer(invocation -> {
                    NotificationOutbox o = invocation.getArgument(0);
                    o.setId("outbox-novo");
                    return o;
                });

        service.resend("fulano@email.com");

        verify(resendRateLimitService).recordResend("fulano@email.com");
        ArgumentCaptor<List<NotificationOutbox>> savedAllCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).saveAll(savedAllCaptor.capture());
        assertThat(savedAllCaptor.getValue()).extracting(NotificationOutbox::getStatus)
                .containsExactly(NotificationStatus.SUPERSEDED);
        verify(dispatchService).dispatchEmailVerification(anyString(), any(EmailVerificationRequestDTO.class));
    }

    @Test
    void resend_deveChamarIssueVerificationEmail_apenasUmaVez_quandoNaoThrottled() {
        User user = buildUser("user-1", "fulano@email.com", false);

        when(userRepository.findByEmail("fulano@email.com")).thenReturn(Optional.of(user));
        when(resendRateLimitService.isThrottled("fulano@email.com")).thenReturn(false);
        when(outboxRepository.findByUserIdAndTypeAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(tokenService.generateToken()).thenReturn("token-novo");
        when(tokenService.hashToken("token-novo")).thenReturn("hash-novo");
        when(tokenService.getTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(outboxRepository.save(any(NotificationOutbox.class)))
                .thenAnswer(invocation -> {
                    NotificationOutbox o = invocation.getArgument(0);
                    o.setId("outbox-novo");
                    return o;
                });

        service.resend("fulano@email.com");

        verify(dispatchService, times(1)).dispatchEmailVerification(anyString(), any());
    }

    // ── resendByUserId ───────────────────────────────────────────────────────

    @Test
    void resendByUserId_deveLancarDomainEntityNotFound_quandoIdInexistente() {
        when(userRepository.findById("id-inexistente")).thenReturn(Optional.empty());

        assertThrows(DomainEntityNotFound.class, () -> service.resendByUserId("id-inexistente"));

        verify(outboxRepository, never()).findByUserIdAndTypeAndStatusIn(any(), any(), any());
        verify(dispatchService, never()).dispatchEmailVerification(any(), any());
    }

    @Test
    void resendByUserId_naoDeveFazerNada_quandoEmailJaVerificado() {
        User user = buildUser("user-1", "fulano@email.com", true);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        service.resendByUserId("user-1");

        verify(resendRateLimitService, never()).isThrottled(anyString());
        verify(dispatchService, never()).dispatchEmailVerification(any(), any());
    }

    @Test
    void resendByUserId_deveChamarIssueVerificationEmail_quandoNaoVerificadoENaoThrottled() {
        User user = buildUser("user-1", "fulano@email.com", false);

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(resendRateLimitService.isThrottled("fulano@email.com")).thenReturn(false);
        when(outboxRepository.findByUserIdAndTypeAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(tokenService.generateToken()).thenReturn("token-novo");
        when(tokenService.hashToken("token-novo")).thenReturn("hash-novo");
        when(tokenService.getTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(outboxRepository.save(any(NotificationOutbox.class)))
                .thenAnswer(invocation -> {
                    NotificationOutbox o = invocation.getArgument(0);
                    o.setId("outbox-novo");
                    return o;
                });

        service.resendByUserId("user-1");

        verify(resendRateLimitService).recordResend("fulano@email.com");
        verify(dispatchService, times(1)).dispatchEmailVerification(anyString(), any());
    }
}
