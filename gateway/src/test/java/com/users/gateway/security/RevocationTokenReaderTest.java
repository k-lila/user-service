package com.users.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * AC-18/AC-19 no nível da unidade que carrega a decisão: o leitor ignora {@code exp} e <b>não</b>
 * ignora assinatura. Com JWKS real servido por WireMock e tokens RS256 reais — decoder mockado
 * responderia a pergunta errada, porque o defeito estava no próprio decoder.
 */
class RevocationTokenReaderTest {

    private static final WireMockServer jwksServer =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final JwksTestSupport jwks = new JwksTestSupport();

    private RevocationTokenReader reader;

    @BeforeAll
    static void startServer() {
        jwksServer.start();
        jwks.publicarJwks(jwksServer);
    }

    @AfterAll
    static void stopServer() {
        jwksServer.stop();
    }

    private RevocationTokenReader reader() {
        if (reader == null) {
            reader = new RevocationTokenReader(jwks.jwkSetUri(jwksServer));
        }
        return reader;
    }

    @Test
    @DisplayName("AC-18: token EXPIRADO é lido normalmente — userID e iat saem intactos")
    void deveLerTokenExpirado() {
        Instant emitidoEm = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        String token = jwks.tokenAssinado("user-1", emitidoEm, Instant.now().minus(25, ChronoUnit.MINUTES));

        RevocationTokenReader.TokenIdentity identidade = reader().read(token).block();

        assertThat(identidade).isNotNull();
        assertThat(identidade.userID()).isEqualTo("user-1");
        assertThat(identidade.issuedAt()).isEqualTo(emitidoEm);
    }

    @Test
    @DisplayName("Token válido (não expirado) também é lido — o exp simplesmente não participa")
    void deveLerTokenValido() {
        Instant emitidoEm = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String token = jwks.tokenAssinado("user-2", emitidoEm, Instant.now().plus(5, ChronoUnit.MINUTES));

        RevocationTokenReader.TokenIdentity identidade = reader().read(token).block();

        assertThat(identidade).isNotNull();
        assertThat(identidade.userID()).isEqualTo("user-2");
    }

    @Test
    @DisplayName("AC-19: assinatura inválida PROPAGA erro — o leitor não é permissivo com forjaria")
    void deveFalhar_quandoAssinaturaInvalida() {
        // Sem esta verificação, qualquer um forjaria um token com o userID de outro e um iat à
        // escolha, e a checagem de revogação passaria a ser controlada pelo atacante.
        String token = jwks.tokenComAssinaturaInvalida("user-3",
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> reader().read(token).block()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("AC-19: token não-parseável propaga erro (o filtro é quem decide o fail-open)")
    void deveFalhar_quandoTokenNaoParseavel() {
        assertThatThrownBy(() -> reader().read("isto-nao-e-um-jwt").block())
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Token sem userID/iat resulta em Mono vazio — nada a checar, não é erro")
    void deveDevolverVazio_quandoSemUserIdOuIat() {
        String semUserId = jwks.tokenAssinado(null,
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES));

        assertThat(reader().read(semUserId).block()).isNull();
    }

    @Test
    @DisplayName("AC-31: o leitor NÃO é atribuível a ReactiveJwtDecoder")
    void deveNaoSerUmReactiveJwtDecoder() {
        // Guarda de tipo do blocker B-1: se um dia alguém "simplificar" fazendo a classe implementar
        // ReactiveJwtDecoder, ela vira candidata a bean daquele tipo, desliga a autoconfig do resource
        // server (@ConditionalOnMissingBean) e o gateway passa a aceitar bearer expirado.
        assertThat(ReactiveJwtDecoder.class.isAssignableFrom(RevocationTokenReader.class))
                .as("RevocationTokenReader nunca pode ser um ReactiveJwtDecoder")
                .isFalse();
    }
}
