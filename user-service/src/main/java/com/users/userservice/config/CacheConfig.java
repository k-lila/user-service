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

import com.users.userservice.dtos.AuthDTO;
import com.users.userservice.dtos.UserResponseDTO;

@EnableCaching
@Configuration
public class CacheConfig {

    @Bean
    @Primary
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();

        RedisCacheConfiguration userConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(keySerializer)
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new JacksonJsonRedisSerializer<>(UserResponseDTO.class)
                )
            )
            .entryTtl(Duration.ofMinutes(5));

        RedisCacheConfiguration authConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(keySerializer)
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new JacksonJsonRedisSerializer<>(AuthDTO.class)
                )
            )
            .entryTtl(Duration.ofMinutes(5));

        return RedisCacheManager.builder(connectionFactory)
            .withCacheConfiguration("usersById", userConfig)
            .withCacheConfiguration("usersByEmail", userConfig)
            .withCacheConfiguration("authByEmail", authConfig)
            .build();
    }
}
