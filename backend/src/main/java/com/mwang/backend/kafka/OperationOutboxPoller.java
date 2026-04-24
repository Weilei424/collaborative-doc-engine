package com.mwang.backend.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OperationOutboxPoller {

    private final OutboxRowProcessor rowProcessor;

    @Value("${collaboration.outbox.batch-size:100}")
    private int batchSize;

    public OperationOutboxPoller(OutboxRowProcessor rowProcessor) {
        this.rowProcessor = rowProcessor;
    }

    @Scheduled(fixedDelayString = "${collaboration.outbox.poll-interval-ms:500}")
    public void poll() {
        Instant now = Instant.now();
        for (int i = 0; i < batchSize; i++) {
            if (!rowProcessor.claimAndProcess(now)) {
                break;
            }
        }
    }
}
