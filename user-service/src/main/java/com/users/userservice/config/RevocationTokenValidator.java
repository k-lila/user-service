package com.users.userservice.config;

import java.time.Instant;
import java.util.OptionalLong;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import com.users.userservice.services.TokenRevocationService;

/**
 * Validador de resource server (ADR-017): rejeita, a cada request, o access token cujo {@code iat}
 * precede o epoch de revogação do titular ({@code userID}) — i.e., emitido antes de uma revogação
 * de role / desativação / hard-delete. Encaixa-se nos validadores default (issuer/exp) via
 * {@code DelegatingOAuth2TokenValidator} no {@code JwtDecoder}.
 *
 * <p>O {@code iat} do JWT tem precisão de segundos; o epoch de revogação, de millis. A comparação
 * estrita ({@code <}) rejeita também um token emitido no <i>mesmo segundo</i> da revogação — sentido
 * seguro (rejeita em empate). Um re-login após a revogação leva mais de 1s na prática, então não
 * tropeça nessa borda. Fail-open herdado de {@link TokenRevocationService#revokedAtMillis(String)}.
 */
public class RevocationTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED =
            new OAuth2Error("token_revoked", "O token foi revogado", null);

    private final TokenRevocationService revocationService;

    public RevocationTokenValidator(TokenRevocationService revocationService) {
        this.revocationService = revocationService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String userID = token.getClaimAsString("userID");
        Instant issuedAt = token.getIssuedAt();
        if (userID == null || issuedAt == null) {
            return OAuth2TokenValidatorResult.success();
        }
        OptionalLong revokedAt = revocationService.revokedAtMillis(userID);
        if (revokedAt.isPresent() && issuedAt.toEpochMilli() < revokedAt.getAsLong()) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
