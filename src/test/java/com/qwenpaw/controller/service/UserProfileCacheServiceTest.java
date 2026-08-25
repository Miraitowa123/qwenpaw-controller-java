package com.qwenpaw.controller.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwenpaw.controller.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileCacheServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final UserCenterClient userCenterClient = mock(UserCenterClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserProfileCacheService service =
            new UserProfileCacheService(redisTemplate, objectMapper, userCenterClient);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void doesNotCallUserCenterWhenRedisCacheExists() throws Exception {
        when(valueOperations.get("qwenpaw:user-profile:2000000248"))
                .thenReturn("{\"userName\":\"李志城\",\"loginName\":\"lizc\"}");

        UserProfile profile = service.cacheIfAbsent("2000000248").orElseThrow();

        assertThat(profile.userName()).isEqualTo("李志城");
        assertThat(profile.loginName()).isEqualTo("lizc");
        verify(userCenterClient, never()).queryUserProfile("2000000248");
    }

    @Test
    void queriesUserCenterAndCachesProfileWhenRedisKeyIsMissing() throws Exception {
        UserProfile profile = new UserProfile("李志城", "lizc");
        when(valueOperations.get("qwenpaw:user-profile:2000000248")).thenReturn(null);
        when(userCenterClient.queryUserProfile("2000000248")).thenReturn(profile);

        assertThat(service.cacheIfAbsent("2000000248")).contains(profile);

        verify(userCenterClient).queryUserProfile("2000000248");
        verify(valueOperations).set(
                "qwenpaw:user-profile:2000000248",
                "{\"userName\":\"李志城\",\"loginName\":\"lizc\"}");
    }

    @Test
    void batchListReadUsesRedisOnly() throws Exception {
        when(valueOperations.multiGet(List.of(
                "qwenpaw:user-profile:2000000248",
                "qwenpaw:user-profile:2000000249")))
                .thenReturn(List.of(
                        "{\"userName\":\"李志城\",\"loginName\":\"lizc\"}",
                        "{\"userName\":\"张三\",\"loginName\":\"zhangs\"}"));

        assertThat(service.getCachedProfiles(List.of("2000000248", "2000000249")))
                .containsKeys("2000000248", "2000000249");
        verify(userCenterClient, never()).queryUserProfile("2000000248");
        verify(userCenterClient, never()).queryUserProfile("2000000249");
    }
}
