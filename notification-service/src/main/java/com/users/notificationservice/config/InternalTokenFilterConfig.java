package com.users.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

// Registra o InternalTokenFilter via FilterRegistrationBean — não há Spring Security
// neste serviço (sem outra rota autenticável), então o filtro de servlet simples é
// registrado diretamente, restrito ao padrão /internal/*.
@Configuration
public class InternalTokenFilterConfig {

    @Value("${internal.api.token}")
    private String internalToken;

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalTokenFilter(internalToken, objectMapper));
        registration.addUrlPatterns("/internal/*");
        return registration;
    }
}
