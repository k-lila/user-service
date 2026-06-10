package com.users.gateway.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;



@Configuration
public class CORSConfig {

    private static final Logger log = LoggerFactory.getLogger(CORSConfig.class);

    // CORS só na borda que o browser toca (C12). Origens externalizadas e configuráveis
    // por ambiente: CSV em cors.allowed-origins (CORS_ALLOWED_ORIGINS no ambiente),
    // default dev http://localhost:5173. Prod deve setar a origem pública real.
    @Value("${cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        // setAllowedOriginPatterns (não setAllowedOrigins): allowCredentials(true) proíbe
        // o curinga "*" em allowedOrigins; patterns aceita curinga quando necessário.
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Authorization"));

        // Loga a allowlist efetiva no startup: origem mal formada (scheme errado, "/" no
        // fim) quebra o SPA com erro CORS difícil de diagnosticar.
        log.info("| [CORS] | allowlist efetiva | origins: {}", allowedOrigins);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }

}
