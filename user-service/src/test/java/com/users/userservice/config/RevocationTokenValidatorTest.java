package com.users.userservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import com.users.userservice.services.TokenRevocationService;

@ExtendWith(MockitoExtension.class)
class RevocationTokenValidatorTest {

    @Mock private TokenRevocationService revocationService;

    private Jwt jwt(String userID, Instant issuedAt) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "x");
        if (userID != null) {
            builder.claim("userID", userID);
        }
        if (issuedAt != null) {
            builder.issuedAt(issuedAt);
        }
        return builder.build();
    }

    @Test
    void devePassar_quandoNaoHaRevogacao() {
        when(revocationService.revokedAtMillis("user-1")).thenReturn(OptionalLong.empty());

        var result = new RevocationTokenValidator(revocationService)
                .validate(jwt("user-1", Instant.ofEpochMilli(1_700_000_000_000L)));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void devePassar_quandoTokenEmitidoAposARevogacao() {
        when(revocationService.revokedAtMillis("user-1")).thenReturn(OptionalLong.of(1_700_000_000_000L));

        var result = new RevocationTokenValidator(revocationService)
                .validate(jwt("user-1", Instant.ofEpochMilli(1_700_000_060_000L)));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void deveFalhar_quandoTokenEmitidoAntesDaRevogacao() {
        when(revocationService.revokedAtMillis("user-1")).thenReturn(OptionalLong.of(1_700_000_060_000L));

        var result = new RevocationTokenValidator(revocationService)
                .validate(jwt("user-1", Instant.ofEpochMilli(1_700_000_000_000L)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> "token_revoked".equals(e.getErrorCode()));
    }

    @Test
    void devePassar_quandoTokenSemClaimUserId() {
        var result = new RevocationTokenValidator(revocationService)
                .validate(jwt(null, Instant.ofEpochMilli(1_700_000_000_000L)));

        assertThat(result.hasErrors()).isFalse();
    }
}
