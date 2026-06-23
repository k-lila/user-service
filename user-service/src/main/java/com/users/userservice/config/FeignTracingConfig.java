package com.users.userservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

// Propaga o contexto de trace (B3) na chamada Feign ao notification-service.
//
// Mesmo problema já resolvido no authorization-server (ver FeignTracingConfig lá): na cadeia
// Feign + Spring Cloud CircuitBreaker a instrumentação automática (feign-micrometer) registra
// o span cliente no trace correto, mas não emite os headers B3 — sem este interceptor, o
// notification-service receberia a request sem X-B3-* e abriria um trace órfão em vez de
// continuar como filho do user-service.
//
// Injeta explicitamente o contexto de trace corrente no template da request (formato
// configurado em management.tracing.propagation: b3) via o Propagator do Micrometer Tracing.
// Degrada graciosamente quando não há contexto ativo (ex.: chamada fora de uma request HTTP).
@Configuration
public class FeignTracingConfig {

    @Bean
    public RequestInterceptor traceContextInterceptor(Tracer tracer, Propagator propagator) {
        return template -> {
            TraceContext ctx = tracer.currentTraceContext().context();
            if (ctx != null) {
                propagator.inject(ctx, template, (carrier, key, value) -> carrier.header(key, value));
            }
        };
    }
}
