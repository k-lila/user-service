package com.users.gateway.security;

import java.time.Instant;
import java.util.Date;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Par RSA + JWKS servido por WireMock para exercitar decoders de JWT com <b>tokens RS256 reais</b>.
 *
 * <p>Existe porque a correção (5) do ADR-025 não é verificável com decoder mockado: o defeito era
 * justamente a validação de {@code exp} feita <i>pelo decoder</i>, então um mock que devolve um
 * {@code Jwt} pronto responde a pergunta errada. É a mesma razão pela qual
 * {@code JwtDecoderStrictnessIntegrationTest} não herda de {@code AbstractGatewayIntegrationTest},
 * que mocka o {@code ReactiveJwtDecoder} para evitar rede no startup.
 */
public final class JwksTestSupport {

    public static final String KEY_ID = "gateway-test-key";

    private final RSAKey rsaKey;
    private final RSAKey chaveIntrusa;

    public JwksTestSupport() {
        try {
            this.rsaKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            // Chave NÃO publicada no JWKS: assina tokens cuja verificação tem de falhar.
            this.chaveIntrusa = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Publica o JWK set público em {@code /oauth2/jwks} do WireMock informado. */
    public void publicarJwks(WireMockServer server) {
        server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/oauth2/jwks"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(rsaKey.toPublicJWK()).toString())));
    }

    public String jwkSetUri(WireMockServer server) {
        return server.baseUrl() + "/oauth2/jwks";
    }

    /** Token assinado pela chave legítima. {@code expiresAt} no passado produz um token expirado. */
    public String tokenAssinado(String userID, Instant issuedAt, Instant expiresAt) {
        return assinar(rsaKey, userID, issuedAt, expiresAt);
    }

    /** Token bem-formado, porém assinado por chave que não está no JWKS. */
    public String tokenComAssinaturaInvalida(String userID, Instant issuedAt, Instant expiresAt) {
        return assinar(chaveIntrusa, userID, issuedAt, expiresAt);
    }

    private String assinar(RSAKey chave, String userID, Instant issuedAt, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("titular@test.com")
                    .claim("userID", userID)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chave.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(chave));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
