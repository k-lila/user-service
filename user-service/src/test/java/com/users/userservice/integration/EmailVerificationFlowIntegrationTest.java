package com.users.userservice.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.users.userservice.domain.NotificationOutbox;
import com.users.userservice.domain.NotificationStatus;
import com.users.userservice.domain.NotificationType;
import com.users.userservice.domain.User;
import com.users.userservice.dtos.UserRequestDTO;
import com.users.userservice.dtos.UserResponseDTO;
import com.users.userservice.exceptions.InvalidVerificationTokenException;
import com.users.userservice.repository.INotificationOutboxRepository;
import com.users.userservice.repository.IUserRepository;
import com.users.userservice.services.EmailVerificationService;
import com.users.userservice.services.RegisterService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Integração da verificação de e-mail (ADR-015): registro → outbox → Feign para o
 * notification-service (substituído por WireMock) → confirmação/reenvio com Mongo+Redis reais
 * (Testcontainers). O notification-service nunca é exposto pelo gateway — aqui ele é simulado
 * no canal interno Feign (mesmo padrão do {@code UserServiceCircuitBreakerIntegrationTest} do
 * authorization-server).
 */
class EmailVerificationFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private RegisterService registerService;
    @Autowired private EmailVerificationService emailVerificationService;
    @Autowired private IUserRepository userRepository;
    @Autowired private INotificationOutboxRepository outboxRepository;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

    protected static final WireMockServer notificationServiceMock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        notificationServiceMock.start();
    }

    @DynamicPropertySource
    static void overrideNotificationServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.notification-service[0].uri",
                notificationServiceMock::baseUrl);
    }

    @BeforeEach
    void limparEstado() {
        userRepository.deleteAll();
        outboxRepository.deleteAll();
        notificationServiceMock.resetAll();
        resetarCircuitBreakers();
    }

    @AfterEach
    void limparEstadoApos() {
        userRepository.deleteAll();
        outboxRepository.deleteAll();
        notificationServiceMock.resetAll();
        resetarCircuitBreakers();
    }

    private void resetarCircuitBreakers() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private UserRequestDTO buildDTO(String nome, String email, String senha) {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName(nome);
        dto.setEmail(email);
        dto.setPassword(senha);
        dto.setTermsAccepted(true);
        return dto;
    }

    private void stubNotificationServiceCom(int status) {
        notificationServiceMock.stubFor(post(urlEqualTo("/internal/notifications/email-verification"))
                .willReturn(aResponse().withStatus(status)));
    }

    private NotificationOutbox unicoOutboxDoUsuario(String userId) {
        List<NotificationOutbox> outboxes = outboxRepository.findAll().stream()
                .filter(o -> o.getUserId().equals(userId))
                .toList();
        assertThat(outboxes).hasSize(1);
        return outboxes.get(0);
    }

    @Test
    void deveCriarOutboxSent_quandoRegistroEnviaComSucesso() {
        stubNotificationServiceCom(200);

        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano@email.com", "senha123"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            NotificationOutbox outbox = unicoOutboxDoUsuario(registrado.getId());
            assertThat(outbox.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(outbox.getType()).isEqualTo(NotificationType.EMAIL_VERIFICATION);
        });
    }

    @Test
    void deveResponder201_masMarcarOutboxFailed_quandoNotificationServiceRetorna500() {
        stubNotificationServiceCom(500);

        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Ciclano", "ciclano@email.com", "senha123"));

        assertThat(registrado).isNotNull();
        assertThat(registrado.getId()).isNotBlank();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            NotificationOutbox outbox = unicoOutboxDoUsuario(registrado.getId());
            assertThat(outbox.getStatus()).isEqualTo(NotificationStatus.FAILED);
        });
    }

    @Test
    void deveRegistrarUsuarioComEmailVerifiedFalse_apesarDeFalhaNoEnvio() {
        stubNotificationServiceCom(500);

        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Beltrano", "beltrano@email.com", "senha123"));

        User persistido = userRepository.findById(registrado.getId()).orElseThrow();
        assertThat(persistido.getEmailVerified()).isFalse();
    }

    @Test
    void confirmacaoComTokenCorreto_devePersistirEmailVerifiedTrueNoMongo() {
        stubNotificationServiceCom(200);

        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano.confirma@email.com", "senha123"));

        NotificationOutbox outbox = await().atMost(Duration.ofSeconds(3))
                .until(() -> {
                    List<NotificationOutbox> all = outboxRepository.findAll().stream()
                            .filter(o -> o.getUserId().equals(registrado.getId()))
                            .toList();
                    return all.isEmpty() ? null : all.get(0);
                }, o -> o != null && o.getStatus() == NotificationStatus.SENT);

        String tokenCapturado = capturarTokenDoLinkEnviado();

        emailVerificationService.confirm(tokenCapturado);

        User confirmado = userRepository.findById(registrado.getId()).orElseThrow();
        assertThat(confirmado.getEmailVerified()).isTrue();
        assertThat(confirmado.getEmailVerifiedAt()).isNotNull();

        NotificationOutbox outboxConfirmado = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(outboxConfirmado.getStatus()).isEqualTo(NotificationStatus.CONFIRMED);
    }

    @Test
    void confirmacaoComTokenInvalido_deveLancarExcecao_semAlterarUsuario() {
        stubNotificationServiceCom(200);

        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano.invalido@email.com", "senha123"));

        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidVerificationTokenException.class,
                () -> emailVerificationService.confirm("token-totalmente-invalido"));

        User aindaNaoVerificado = userRepository.findById(registrado.getId()).orElseThrow();
        assertThat(aindaNaoVerificado.getEmailVerified()).isFalse();
    }

    @Test
    void resendParaContaPendente_deveGerarNovoOutbox_eSupersederOAnterior() {
        stubNotificationServiceCom(200);

        UserResponseDTO registrado = registerService.registerUser(
                buildDTO("Fulano", "fulano.resend@email.com", "senha123"));

        NotificationOutbox primeiroOutbox = await().atMost(Duration.ofSeconds(3))
                .until(() -> unicoOutboxDoUsuario(registrado.getId()),
                        o -> o.getStatus() == NotificationStatus.SENT);

        emailVerificationService.resend("fulano.resend@email.com");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            List<NotificationOutbox> outboxes = outboxRepository.findAll().stream()
                    .filter(o -> o.getUserId().equals(registrado.getId()))
                    .toList();
            assertThat(outboxes).hasSize(2);

            Optional<NotificationOutbox> antigo = outboxes.stream()
                    .filter(o -> o.getId().equals(primeiroOutbox.getId()))
                    .findFirst();
            assertThat(antigo).isPresent();
            assertThat(antigo.get().getStatus()).isEqualTo(NotificationStatus.SUPERSEDED);

            Optional<NotificationOutbox> novo = outboxes.stream()
                    .filter(o -> !o.getId().equals(primeiroOutbox.getId()))
                    .findFirst();
            assertThat(novo).isPresent();
            assertThat(novo.get().getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    @Test
    void circuitBreakerDeveAbrir_aposFalhasRepetidas_eMarcarOutboxesFailedSemDerrubarRegistro() {
        stubNotificationServiceCom(500);

        UserResponseDTO primeiro = registerService.registerUser(
                buildDTO("Um", "cb.um@email.com", "senha123"));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(unicoOutboxDoUsuario(primeiro.getId()).getStatus())
                        .isEqualTo(NotificationStatus.FAILED));

        UserResponseDTO segundo = registerService.registerUser(
                buildDTO("Dois", "cb.dois@email.com", "senha123"));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(unicoOutboxDoUsuario(segundo.getId()).getStatus())
                        .isEqualTo(NotificationStatus.FAILED));

        notificationServiceMock.resetAll();
        stubNotificationServiceCom(200);

        UserResponseDTO terceiro = registerService.registerUser(
                buildDTO("Tres", "cb.tres@email.com", "senha123"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(unicoOutboxDoUsuario(terceiro.getId()).getStatus())
                        .isEqualTo(NotificationStatus.FAILED));

        assertThat(terceiro).isNotNull();
        assertThat(terceiro.getId()).isNotBlank();
    }

    private String capturarTokenDoLinkEnviado() {
        var requests = notificationServiceMock.getAllServeEvents();
        assertThat(requests).isNotEmpty();
        String body = requests.get(0).getRequest().getBodyAsString();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
