package com.mwang.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class KafkaConfig {

    @Bean
    public RetryTemplate kafkaRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(200, 2, 1600)
                .retryOn(Exception.class)
                .build();
    }
}
