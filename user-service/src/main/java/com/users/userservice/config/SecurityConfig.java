package com.users.userservice.config;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.function.SingletonSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.users.userservice.services.TokenRevocationService;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${internal.api.token}")
    private String internalToken;

    @Autowired
    private ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Decoder do resource server com revogação ativa (ADR-017): aos validadores default
     * (issuer/exp) soma o {@link RevocationTokenValidator}. A resolução do metadata OIDC é
     * <b>preguiçosa</b> ({@link SingletonSupplier}, na 1ª decodificação) — preserva a
     * independência de startup do user-service em relação ao authorization-server, como faz a
     * autoconfig por issuer-uri.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            TokenRevocationService revocationService) {
        SingletonSupplier<JwtDecoder> delegate = SingletonSupplier.of(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
            OAuth2TokenValidator<Jwt> withRevocation = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuerUri),
                    new RevocationTokenValidator(revocationService));
            decoder.setJwtValidator(withRevocation);
            return decoder;
        });
        return token -> delegate.obtain().decode(token);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("roles");
        converter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(converter);

        return jwtConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // CORS removido (C12): o user-service nunca é chamado direto pelo browser
            // (só via gateway, que faz CORS na borda); aqui o guard é JWT + isolamento de rede.
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/internal/**",
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/v1/users/register",
                        "/v1/users/verify-email"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .addFilterBefore(new InternalTokenFilter(internalToken, objectMapper), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

}
