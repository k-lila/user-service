package authorizationserver.config;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
public class JWKConfig {

	// Par RSA fixo carregado de PEM (kid estável compartilhado entre instâncias).
	// Substitui a geração por boot, que quebrava a validação JWT com N instâncias e
	// invalidava tokens a cada restart. Defaults de classpath são chaves DE DESENVOLVIMENTO
	// (gap de segurança conhecido); em produção sobrescrever via JWK_* (secret montado).
	@Value("${jwk.private-key}")
	private Resource privateKeyPem;

	@Value("${jwk.public-key}")
	private Resource publicKeyPem;

	@Value("${jwk.key-id}")
	private String keyId;

	@Bean
	public JWKSource<SecurityContext> jwkSource() {
		RSAPrivateKey privateKey = readPrivateKey();
		RSAPublicKey publicKey = readPublicKey();
		RSAKey rsaKey = new RSAKey.Builder(publicKey)
				.privateKey(privateKey)
				.keyID(keyId)
				.build();
		JWKSet jwkSet = new JWKSet(rsaKey);
		return new ImmutableJWKSet<>(jwkSet);
	}

	private RSAPrivateKey readPrivateKey() {
		try (InputStream in = privateKeyPem.getInputStream()) {
			return RsaKeyConverters.pkcs8().convert(in);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Falha ao carregar a chave privada JWK", ex);
		}
	}

	private RSAPublicKey readPublicKey() {
		try (InputStream in = publicKeyPem.getInputStream()) {
			return RsaKeyConverters.x509().convert(in);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Falha ao carregar a chave pública JWK", ex);
		}
	}

	@Bean
	public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
		return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
	}

}
