package com.users.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {
    
    @Primary
    @Bean
    public RedisRateLimiter redisRateLimiterHigh() {
        return new RedisRateLimiter(10, 20);
    }

    @Bean
    public RedisRateLimiter redisRateLimiterMed() {
        return new RedisRateLimiter(5, 10);
    }

    @Bean
    public RedisRateLimiter redisRateLimiterLow() {
        return new RedisRateLimiter(2, 5);
    }

    @Primary
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            var headers = exchange.getRequest().getHeaders();
            String ip = headers.getFirst("X-Forwarded-For");
            if (ip != null) {
                return Mono.just(ip.split(",")[0]);
            }      
            var address = exchange.getRequest().getRemoteAddress();
            if (address == null) return Mono.just("unknown");
            return Mono.just(address.getAddress().getHostAddress());
        };
    }

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange ->
            exchange.getPrincipal()
                .map(principal -> principal.getName())
                .defaultIfEmpty("anonymous");
    }

}
