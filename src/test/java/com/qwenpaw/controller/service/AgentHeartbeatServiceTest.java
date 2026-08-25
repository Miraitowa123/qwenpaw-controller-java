package com.qwenpaw.controller.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentHeartbeatServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final AgentHeartbeatService service = new AgentHeartbeatService(redisTemplate);

    @Test
    void readsLastAccessAndActualRedisTtl() {
        long timestamp = 1_723_982_400_000L;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("agent:alive:10001")).thenReturn(String.valueOf(timestamp));
        when(redisTemplate.getExpire("agent:alive:10001", TimeUnit.SECONDS)).thenReturn(259_199L);

        AgentHeartbeatService.HeartbeatStatus status = service.getStatus("10001");

        assertThat(status.lastAccess().toInstant()).isEqualTo(Instant.ofEpochMilli(timestamp));
        assertThat(status.ttlSeconds()).isEqualTo(259_199L);
    }

    @Test
    void returnsEmptyStatusWhenHeartbeatDoesNotExist() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("agent:alive:10002")).thenReturn(null);

        AgentHeartbeatService.HeartbeatStatus status = service.getStatus("10002");

        assertThat(status.lastAccess()).isNull();
        assertThat(status.ttlSeconds()).isNull();
    }
}
