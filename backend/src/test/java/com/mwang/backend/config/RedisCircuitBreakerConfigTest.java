package com.mwang.backend.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCircuitBreakerConfigTest {

    @Test
    void breakerOpensAfterSufficientFailures() {
        RedisCircuitBreakerConfig cfg = new RedisCircuitBreakerConfig();
        // window=5, minimumCalls=5, failureRate=40%, halfOpen=3, waitMs=3000
        CircuitBreaker breaker = cfg.redisPublishCircuitBreaker(40f, 5, 5, 3, 3000L);

        // 5 consecutive failures → 100% failure rate > 40% threshold → OPEN
        for (int i = 0; i < 5; i++) {
            try {
                breaker.executeRunnable(() -> { throw new RuntimeException("redis down"); });
            } catch (Exception ignored) {}
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
