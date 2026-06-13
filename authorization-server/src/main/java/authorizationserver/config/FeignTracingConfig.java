package authorizationserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

// Propaga o contexto de trace (B3) na chamada Feign ao user-service.
//
// Na cadeia Feign + Spring Cloud CircuitBreaker (feign.circuitbreaker.enabled) a
// instrumentação automática do feign-micrometer registra o span cliente no trace correto,
// mas não emite os headers B3 com esse contexto: o user-service recebia a request sem
// X-B3-* e abria um trace NOVO (órfão) em vez de continuar como filho do auth-server.
//
// Este interceptor injeta explicitamente o contexto de trace corrente no template da
// request, no formato configurado em management.tracing.propagation (b3), via o Propagator
// do Micrometer Tracing. Assim o user-service extrai o contexto (consume: b3) e o span vira
// filho no mesmo trace. Se não houver contexto ativo, não injeta nada (degradação graciosa).
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
