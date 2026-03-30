package com.mwang.backend;

import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BackendApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
        // Verifies the full Spring application context starts successfully
        // against real Postgres, Redis, and Kafka containers.
    }
}
