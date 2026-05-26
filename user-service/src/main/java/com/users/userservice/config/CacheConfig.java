package com.users.userservice.config;

import java.time.Duration;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.users.userservice.dtos.UserResponseDTO;

@EnableCaching
@Configuration
public class CacheConfig {

    @Bean
    @Primary
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        JacksonJsonRedisSerializer<UserResponseDTO> serializer =
            new JacksonJsonRedisSerializer<>(UserResponseDTO.class);
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(serializer)
            )
            .entryTtl(Duration.ofMinutes(5));
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }
}
