package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.DocumentTreeCache;
import com.mwang.backend.collaboration.OperationTransformer;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.model.DocumentNode;
import com.mwang.backend.domain.model.DocumentTree;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatcherDebugTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentOperationRepository operationRepository;
    @Mock private DocumentOperationCommitter committer;
    @Mock private DocumentAuthorizationService authorizationService;
    @Mock private OperationTransformer transformer;
    @Mock private DocumentTreeCache treeCache;
    @Mock private CollaborationBroadcastService broadcastService;
    @Mock private RedisCollaborationEventPublisher redisPublisher;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @Test
    void debugCrossThreadInvocation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        
        UUID documentId = UUID.randomUUID();
        User actor = User.builder().id(UUID.randomUUID()).username("alice").build();
        DocumentTree emptyTree = new DocumentTree(
                List.of(DocumentNode.builder().type("paragraph").text("").build()));
        String content = objectMapper.writeValueAsString(emptyTree);
        System.err.println("Serialized content: " + content);
        
        Document document = Document.builder()
                .id(documentId).currentVersion(0L)
                .content(content)
                .owner(actor).visibility(DocumentVisibility.PRIVATE).build();

        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of());
        when(treeCache.get(any(), anyLong())).thenReturn(Optional.empty());
        doNothing().when(authorizationService).assertCanWrite(any(), any());
        lenient().when(transformer.transform(any(), any(), any(), any()))
                .thenReturn(Optional.of(objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")));
        CountDownLatch latch = new CountDownLatch(1);
        when(committer.commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList()))
                .thenAnswer(inv -> {
                    System.err.println("[DEBUG] commitBatch called! Thread: " + Thread.currentThread().getName());
                    List<BatchedOpData> ops = inv.getArgument(5);
                    latch.countDown();
                    return ops.stream().map(op -> DocumentOperation.builder()
                            .operationId(op.operationId()).serverVersion(op.serverVersion())
                            .operationType(op.operationType())
                            .payload("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")
                            .clientSessionId(op.clientSessionId())
                            .document(document).actor(actor).baseVersion(op.baseVersion())
                            .build()).toList();
                });

        DocumentOperationBatcher batcher = new DocumentOperationBatcher(
                documentRepository, operationRepository, committer,
                authorizationService, transformer, objectMapper, treeCache,
                broadcastService, redisPublisher, messagingTemplate,
                meterRegistry, 200, 3, 50, 5L, 5000L);

        JsonNode payload = objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
        PendingOperation op = new PendingOperation(documentId,
                new com.mwang.backend.web.model.SubmitOperationRequest(
                        UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload),
                actor, "session-1", "alice");

        batcher.enqueue(op);

        boolean called = latch.await(500, TimeUnit.MILLISECONDS);
        System.err.println("[DEBUG] latch.await result: " + called);
        
        if (called) {
            verify(committer, atLeastOnce()).commitBatch(
                    eq(documentId), anyLong(), anyLong(), anyString(), any(), anyList());
            System.err.println("[DEBUG] verify PASSED");
        } else {
            System.err.println("[DEBUG] commitBatch was NEVER called!");
        }
        assertThat(called).isTrue();
    }
}
