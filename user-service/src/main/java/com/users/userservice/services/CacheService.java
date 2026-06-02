package com.users.userservice.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.users.userservice.dtos.UserResponseDTO;

@Service
public class CacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheService.class);

    private final CacheManager cacheManager;

    public CacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void putById(String userID, UserResponseDTO dto) {
        Cache cache = cacheManager.getCache("usersById");
        if (cache != null) {
            cache.put(userID, dto);
            LOGGER.info("| cache usersById | put | id: {}", userID);
        }
    }

    public void evictById(String userID) {
        Cache cache = cacheManager.getCache("usersById");
        if (cache != null) {
            cache.evict(userID);
            LOGGER.info("| cache usersById | evict | id: {}", userID);
        }
    }

    public void putByEmail(String email, UserResponseDTO dto) {
        Cache cache = cacheManager.getCache("usersByEmail");
        if (cache != null) {
            cache.put(email, dto);
            LOGGER.info("| cache usersByEmail | put | email: {}", email);
        }
    }

    public void evictByEmail(String email) {
        Cache cache = cacheManager.getCache("usersByEmail");
        if (cache != null) {
            cache.evict(email);
            LOGGER.info("| cache usersByEmail | evict | email: {}", email);
        }
    }

    public void evictByEmailAuth(String email) {
        Cache cache = cacheManager.getCache("authByEmail");
        if (cache != null) {
            cache.evict(email);
            LOGGER.info("| cache authByEmail | evict | email: {}", email);
        }
    }
}
