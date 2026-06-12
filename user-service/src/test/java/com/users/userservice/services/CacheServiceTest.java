package com.users.userservice.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.users.userservice.dtos.UserResponseDTO;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    private CacheService service() {
        return new CacheService(cacheManager);
    }

    private UserResponseDTO dto() {
        UserResponseDTO dto = new UserResponseDTO();
        dto.setId("id-1");
        dto.setEmail("fulano@email.com");
        return dto;
    }

    @Test
    void devePutById_quandoCacheExiste() {
        when(cacheManager.getCache("usersById")).thenReturn(cache);
        UserResponseDTO dto = dto();

        service().putById("id-1", dto);

        verify(cache).put("id-1", dto);
    }

    @Test
    void deveEvictById_quandoCacheExiste() {
        when(cacheManager.getCache("usersById")).thenReturn(cache);

        service().evictById("id-1");

        verify(cache).evict("id-1");
    }

    @Test
    void devePutByEmail_quandoCacheExiste() {
        when(cacheManager.getCache("usersByEmail")).thenReturn(cache);
        UserResponseDTO dto = dto();

        service().putByEmail("fulano@email.com", dto);

        verify(cache).put("fulano@email.com", dto);
    }

    @Test
    void deveEvictByEmail_quandoCacheExiste() {
        when(cacheManager.getCache("usersByEmail")).thenReturn(cache);

        service().evictByEmail("fulano@email.com");

        verify(cache).evict("fulano@email.com");
    }

    @Test
    void deveEvictByEmailAuth_quandoCacheExiste() {
        when(cacheManager.getCache("authByEmail")).thenReturn(cache);

        service().evictByEmailAuth("fulano@email.com");

        verify(cache).evict("fulano@email.com");
    }

    @Test
    void naoDeveFalhar_quandoCacheNulo() {
        when(cacheManager.getCache(anyString())).thenReturn(null);
        CacheService service = service();
        UserResponseDTO dto = dto();

        assertDoesNotThrow(() -> {
            service.putById("id-1", dto);
            service.evictById("id-1");
            service.putByEmail("fulano@email.com", dto);
            service.evictByEmail("fulano@email.com");
            service.evictByEmailAuth("fulano@email.com");
        });
        verifyNoInteractions(cache);
    }
}
