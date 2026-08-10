package com.users.gateway.security;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Leitor de access token <b>para fins de revogação</b> (ADR-025) — usado só pelo
 * {@code RevocationWebFilter}.
 *
 * <p><b>O problema.</b> A checagem de revogação da borda (ADR-017) precisa de dois campos do JWT
 * guardado na sessão: {@code userID} e {@code iat}. Ela decodificava o token com o decoder do
 * resource server, que valida {@code exp} — então, com o access token <b>expirado</b>, a decodificação
 * lançava e o {@code onErrorResume} pulava a checagem inteira, fail-open. Observado 3× em 35 min de
 * uso normal: caminho comum, não borda. Para esta pergunta o {@code exp} é <b>irrelevante</b> — a
 * pergunta é "quem era o titular e quando este token nasceu", e um token expirado responde as duas.
 *
 * <p><b>Por que um tipo próprio, e não um {@code ReactiveJwtDecoder} leniente.</b> O gateway
 * <b>não declara</b> {@code ReactiveJwtDecoder}: ele vem da autoconfig
 * {@code ReactiveOAuth2ResourceServerJwtConfiguration}, que é
 * {@code @ConditionalOnMissingBean(ReactiveJwtDecoder.class)}. Expor o decoder leniente como bean
 * desse tipo — sob <b>qualquer</b> qualifier — desligaria a autoconfig e o resource server passaria a
 * aceitar <b>bearer JWT expirado</b>: a correção de segurança <i>enfraqueceria</i> o serviço. Dois
 * beans do tipo, por outro lado, tornariam ambígua a injeção. Esta classe elimina a categoria inteira
 * do problema: ela <b>guarda</b> um {@link NimbusReactiveJwtDecoder} como campo privado, não o expõe,
 * não implementa {@link ReactiveJwtDecoder} e não é atribuível a ele.
 *
 * <p><b>Assinatura continua obrigatória.</b> O que se desliga é só a validação de {@code exp}/
 * {@code nbf}/{@code iss} — a verificação criptográfica contra o JWKS permanece, senão qualquer um
 * forjaria um token com o {@code userID} de outro e o {@code iat} que quisesse, e a checagem de
 * revogação passaria a ser controlada pelo atacante. Assinatura inválida ou JWKS inalcançável
 * propagam o erro, e o filtro trata como fail-open (ADR-017).
 *
 * <p>A busca do JWKS é <b>preguiçosa</b> ({@code withJwkSetUri}): não acopla o startup do gateway ao
 * authorization-server.
 */
@Component
public class RevocationTokenReader {

    private final NimbusReactiveJwtDecoder decoder;

    public RevocationTokenReader(
            @Value("${security.revocation.jwk-set-uri:${AUTH_ISSUER_URI:http://localhost:8082}/oauth2/jwks}")
            String jwkSetUri) {
        NimbusReactiveJwtDecoder nimbus = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        // Substitui os validadores default (que incluem o de timestamp) por um que sempre aprova: a
        // verificação de assinatura acontece ANTES, no processamento do JWS, e não é afetada.
        nimbus.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
        this.decoder = nimbus;
    }

    /**
     * Titular e instante de emissão do token, ignorando {@code exp}. {@link Mono#empty()} quando o
     * token não carrega os dois campos (nada a checar); erro quando a assinatura não valida, o token
     * não é parseável ou o JWKS está inalcançável — o chamador é quem decide o fail-open.
     */
    public Mono<TokenIdentity> read(String tokenValue) {
        return this.decoder.decode(tokenValue).flatMap(RevocationTokenReader::toIdentity);
    }

    private static Mono<TokenIdentity> toIdentity(Jwt jwt) {
        String userID = jwt.getClaimAsString("userID");
        Instant issuedAt = jwt.getIssuedAt();
        if (userID == null || issuedAt == null) {
            return Mono.empty();
        }
        return Mono.just(new TokenIdentity(userID, issuedAt));
    }

    /** O mínimo que a checagem de revogação precisa — nada mais do token é exposto. */
    public record TokenIdentity(String userID, Instant issuedAt) {
    }
}
