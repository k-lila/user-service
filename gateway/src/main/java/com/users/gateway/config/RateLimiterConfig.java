package com.users.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.users.gateway.util.ClientIpResolver;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    private final String trustedClientIpHeader;

    public RateLimiterConfig(
            @Value("${security.trusted-client-ip-header:CF-Connecting-IP}") String trustedClientIpHeader) {
        this.trustedClientIpHeader = trustedClientIpHeader;
    }

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
        // IP confiável via CF-Connecting-IP (Cloudflare) e fallback no remoteAddress
        // (que, sob forward-headers-strategy=framework, reflete o XFF sanitizado pela borda).
        return exchange ->
            Mono.just(ClientIpResolver.resolve(exchange.getRequest(), trustedClientIpHeader));
    }

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange ->
            exchange.getPrincipal()
                .map(principal -> principal.getName())
                .defaultIfEmpty("anonymous");
    }

}
