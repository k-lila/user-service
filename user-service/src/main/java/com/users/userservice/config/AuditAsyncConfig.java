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
 * Executor dedicado à escrita assíncrona da trilha de auditoria ({@code AuditService}).
 *
 * <p>A auditoria não pode adicionar latência ao caminho de negócio nem derrubá-lo: roda fora
 * da thread da request. O {@link org.springframework.core.task.TaskDecorator} copia o MDC
 * (traceId/spanId B3) para a thread do executor, preservando a correlação no registro.
 *
 * <p>Política de rejeição {@code CallerRunsPolicy}: sob saturação, a auditoria degrada para
 * síncrona (executa na thread chamadora) em vez de descartar entradas — completude da trilha
 * acima de latência no pico. {@code @EnableAsync} aqui só ativa o {@code @Async} explícito por
 * nome ({@code "auditExecutor"}); demais beans não são afetados.
 */
@Configuration
@EnableAsync
public class AuditAsyncConfig {

    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("audit-");
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
