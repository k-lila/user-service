package com.users.userservice.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor dedicado ao disparo assíncrono do e-mail de verificação (ADR-015) —
 * responsabilidade distinta do {@code auditExecutor} (AuditAsyncConfig), não reaproveitado.
 *
 * <p>A chamada ao notification-service não pode adicionar latência ao cadastro/reenvio nem
 * derrubá-los: roda fora da thread da request. O {@link org.springframework.core.task.TaskDecorator}
 * copia o MDC (traceId/spanId B3) para a thread do executor, preservando a correlação no log
 * e permitindo que o {@code FeignTracingConfig} propague o trace corrente.
 *
 * <p>Política de rejeição {@code CallerRunsPolicy}: sob saturação, degrada para síncrono em vez
 * de descartar o envio — mas isso é só backpressure local; falha de fato (timeout, circuit
 * breaker aberto, 5xx do notification-service) é sempre isolada pelo chamador (RegisterService/
 * EmailVerificationService), que nunca propaga a exceção ao cadastro/reenvio.
 */
@Configuration
@EnableAsync
public class NotificationAsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("notification-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
