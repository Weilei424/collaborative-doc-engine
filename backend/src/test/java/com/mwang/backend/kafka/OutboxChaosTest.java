package com.mwang.backend.kafka;

import com.github.dockerjava.api.DockerClient;
import com.mwang.backend.domain.*;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@TestPropertySource(properties = {
        "collaboration.outbox.poll-interval-ms=100",
        "collaboration.outbox.backoff-ms=500",
        "collaboration.outbox.backoff-cap-ms=2000",
        "collaboration.outbox.max-attempts=20",
        "spring.kafka.producer.properties.delivery.timeout.ms=3000"
})
class OutboxChaosTest extends AbstractIntegrationTest {

    @Autowired DocumentOperationRepository operationRepo;
    @Autowired UserRepository userRepo;
    @Autowired DocumentRepository documentRepo;

    @Test
    void allOpsPublishedExactlyOnceAfterKafkaPauseAndResume() throws Exception {
        User user = userRepo.save(User.builder()
                .username("chaos-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@test.com")
                .build());
        Document doc = documentRepo.save(Document.builder()
                .title("Chaos Doc")
                .content("{\"blocks\":[]}")
                .owner(user)
                .build());

        // Insert 5 ops before pause
        List<UUID> opIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var op = operationRepo.save(DocumentOperation.builder()
                    .document(doc)
                    .actor(user)
                    .operationId(UUID.randomUUID())
                    .clientSessionId("chaos-sess")
                    .baseVersion((long) i)
                    .serverVersion((long) (i + 1))
                    .operationType(DocumentOperationType.INSERT_TEXT)
                    .payload("{\"path\":[0],\"offset\":0,\"text\":\"op" + i + "\"}")
                    .build());
            opIds.add(op.getId());
        }

        // Pause Kafka
        DockerClient docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();

        try {
            // Insert 5 more ops while Kafka is paused — these must queue up in the outbox
            for (int i = 5; i < 10; i++) {
                var op = operationRepo.save(DocumentOperation.builder()
                        .document(doc)
                        .actor(user)
                        .operationId(UUID.randomUUID())
                        .clientSessionId("chaos-sess")
                        .baseVersion((long) i)
                        .serverVersion((long) (i + 1))
                        .operationType(DocumentOperationType.INSERT_TEXT)
                        .payload("{\"path\":[0],\"offset\":0,\"text\":\"op" + i + "\"}")
                        .build());
                opIds.add(op.getId());
            }

            // Wait a moment — poller should be attempting and failing
            Thread.sleep(2000);

            // The paused-window ops (ids 5-9) must NOT be published yet
            for (int i = 5; i < 10; i++) {
                UUID id = opIds.get(i);
                assertThat(operationRepo.findById(id).orElseThrow().getPublishedToKafkaAt())
                        .as("op %d should not be published while Kafka is paused", i)
                        .isNull();
            }
        } finally {
            // Always unpause, even if assertions fail
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        // After resume, all 10 ops must eventually be published
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (UUID id : opIds) {
                assertThat(operationRepo.findById(id).orElseThrow().getPublishedToKafkaAt())
                        .as("op %s should be published after Kafka resumes", id)
                        .isNotNull();
            }
        });

        // No poison rows
        assertThat(operationRepo.countPoison()).isZero();
    }
}
