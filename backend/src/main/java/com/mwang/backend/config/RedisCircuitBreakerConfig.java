package com.mwang.backend.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RedisCircuitBreakerConfig {

    @Bean
    CircuitBreaker redisPublishCircuitBreaker(
            @Value("${collaboration.redis.circuit-breaker.failure-rate-threshold:50}") float failureRate,
            @Value("${collaboration.redis.circuit-breaker.sliding-window-size:20}") int windowSize,
            @Value("${collaboration.redis.circuit-breaker.permitted-calls-in-half-open:3}") int halfOpenCalls,
            @Value("${collaboration.redis.circuit-breaker.wait-duration-in-open-ms:10000}") long waitMs) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRate)
                .slidingWindowSize(windowSize)
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitMs))
                .build();
        return CircuitBreakerRegistry.ofDefaults().circuitBreaker("redis-publish", config);
    }
}
