package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import com.mwang.backend.web.model.OperationErrorResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class StaleClientVersionCapTest extends AbstractIntegrationTest {

    @MockitoBean private CurrentUserProvider currentUserProvider;
    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    @Autowired private DocumentOperationService operationService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentOperationRepository operationRepository;
    @Autowired private UserRepository userRepository;

    private User actor;
    private Document document;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        actor = userRepository.save(User.builder()
                .username("stale-user-" + uid)
                .email("stale-" + uid + "@test.com")
                .passwordHash("hash").build());

        // Seed document at version 201 to trigger the stale cap (default 200)
        document = documentRepository.save(Document.builder()
                .title("Stale Cap Test Doc")
                .content("{\"children\":[{\"type\":\"paragraph\",\"text\":\"\",\"children\":[]}]}")
                .owner(actor)
                .visibility(DocumentVisibility.PRIVATE)
                .currentVersion(201L).build());

        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
    }

    @AfterEach
    void tearDown() {
        operationRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void submitOperation_lagExceedsCap_deliversResyncRequired() throws Exception {
        // currentVersion=201, baseVersion=0 → lag=201 > staleCap=200 → RESYNC_REQUIRED
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        UUID opId = UUID.randomUUID();

        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getUser()).thenReturn(() -> "stale-test-principal");

        operationService.submitOperation(
                document.getId(),
                new SubmitOperationRequest(opId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(messagingTemplate).convertAndSendToUser(
                        eq("stale-test-principal"),
                        eq("/queue/errors." + document.getId()),
                        argThat((OperationErrorResponse r) ->
                                "RESYNC_REQUIRED".equals(r.error())
                                && opId.equals(r.operationId())
                                && Long.valueOf(201L).equals(r.currentServerVersion()))));
    }

    @Test
    void submitOperation_lagEqualsToCap_notRejected() throws Exception {
        // currentVersion=201, baseVersion=1 → lag=200 = staleCap=200 → NOT rejected (strict >)
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        UUID opId = UUID.randomUUID();

        operationService.submitOperation(
                document.getId(),
                new SubmitOperationRequest(opId, 1L, DocumentOperationType.INSERT_TEXT, payload),
                mock(SimpMessageHeaderAccessor.class));

        // Assert the op was actually committed — a > vs >= regression would deliver RESYNC_REQUIRED
        // and leave the document at version 201, so waiting for a committed op at version > 200 proves
        // the boundary condition was not rejected.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var ops = operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                    document.getId(), 200L);
            assertThat(ops).hasSize(1);
            assertThat(ops.get(0).getOperationId()).isEqualTo(opId);
        });
    }
}
