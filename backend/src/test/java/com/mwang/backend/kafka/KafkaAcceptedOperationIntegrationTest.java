package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.*;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest
@TestPropertySource(properties = {
        "collaboration.outbox.poll-interval-ms=100",
        "collaboration.outbox.backoff-ms=500",
        "collaboration.outbox.backoff-cap-ms=2000"
})
class KafkaAcceptedOperationIntegrationTest extends AbstractIntegrationTest {

    @Autowired DocumentOperationRepository operationRepo;
    @Autowired UserRepository userRepo;
    @Autowired DocumentRepository documentRepo;
    @Autowired ObjectMapper objectMapper;

    @MockitoSpyBean
    KafkaOperationNotificationConsumer consumer;

    @Test
    void pollerPublishesOutboxRowAndConsumerReceivesIt() {
        User user = userRepo.save(User.builder()
                .username("outbox-it-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@test.com")
                .build());
        Document doc = documentRepo.save(Document.builder()
                .title("IT Doc")
                .content("{\"blocks\":[]}")
                .owner(user)
                .build());
        operationRepo.save(DocumentOperation.builder()
                .document(doc)
                .actor(user)
                .operationId(UUID.randomUUID())
                .clientSessionId("sess-it")
                .baseVersion(0L)
                .serverVersion(1L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"hello\"}")
                .build());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(consumer).onAcceptedOperation(any()));
    }

    @Test
    void pollerMarksRowPublishedAfterSuccessfulSend() {
        User user = userRepo.save(User.builder()
                .username("outbox-mark-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@test.com")
                .build());
        Document doc = documentRepo.save(Document.builder()
                .title("Mark Doc")
                .content("{\"blocks\":[]}")
                .owner(user)
                .build());
        var op = operationRepo.save(DocumentOperation.builder()
                .document(doc)
                .actor(user)
                .operationId(UUID.randomUUID())
                .clientSessionId("sess-mark")
                .baseVersion(0L)
                .serverVersion(2L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"world\"}")
                .build());

        await().atMost(Duration.ofSeconds(10)).until(() ->
                operationRepo.findById(op.getId())
                        .map(r -> r.getPublishedToKafkaAt() != null)
                        .orElse(false));
    }
}
