package com.users.configserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    // Basic auth no config-server (C17 / G3): o endpoint serve a configuração de
    // todos os serviços; sem auth, qualquer um que alcance a rede interna a lê
    // (incluindo segredos injetados por env). /actuator/health fica aberto para os
    // healthchecks do compose e do config-lb. CSRF off: os clientes são máquinas
    // (Spring Cloud Config), não browser. Credenciais via spring.security.user.* (env).
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
