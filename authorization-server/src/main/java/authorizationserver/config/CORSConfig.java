package authorizationserver.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CORSConfig {

    private static final Logger log = LoggerFactory.getLogger(CORSConfig.class);

    // CORS necessário para o Swagger-UI (C12): diferente do SPA/BFF, o Swagger é um cliente
    // OAuth2 que roda NO BROWSER (servido pela borda, ex.: :8081) e faz fetch CROSS-ORIGIN
    // para /oauth2/token aqui (:8082) ao trocar o code pelo token — esse fetch precisa de
    // CORS. Origem configurável: a origem pública do Swagger (gateway/borda), distinta da
    // origem do SPA que o gateway permite. Default dev http://localhost:8081.
    @Value("${cors.allowed-origins:http://localhost:8081}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        // setAllowedOriginPatterns (não setAllowedOrigins): allowCredentials(true) proíbe
        // o curinga "*" em allowedOrigins; patterns aceita curinga quando necessário.
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        log.info("| [CORS] | allowlist efetiva (Swagger->token) | origins: {}", allowedOrigins);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
