package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.DocumentTreeCache;
import com.mwang.backend.collaboration.OperationTransformer;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.model.DocumentNode;
import com.mwang.backend.domain.model.DocumentTree;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CasMissException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.OperationErrorResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentOperationBatcherTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentOperationRepository operationRepository;
    @Mock private DocumentOperationCommitter committer;
    @Mock private DocumentAuthorizationService authorizationService;
    @Mock private OperationTransformer transformer;
    @Mock private DocumentTreeCache treeCache;
    @Mock private CollaborationBroadcastService broadcastService;
    @Mock private com.mwang.backend.collaboration.RedisCollaborationEventPublisher redisPublisher;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private SimpleMeterRegistry meterRegistry;
    private ObjectMapper objectMapper;
    private DocumentOperationBatcher batcher;

    private UUID documentId;
    private User actor;
    private Document document;
    private final ObjectMapper realMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();
        batcher = new DocumentOperationBatcher(
                documentRepository, operationRepository, committer,
                authorizationService, transformer, objectMapper, treeCache,
                broadcastService, redisPublisher, messagingTemplate,
                meterRegistry, 200, 3, 50, 5L, 5000L);

        documentId = UUID.randomUUID();
        actor = User.builder().id(UUID.randomUUID()).username("alice").build();
        DocumentTree emptyTree = new DocumentTree(
                List.of(DocumentNode.builder().type("paragraph").text("").build()));
        document = Document.builder()
                .id(documentId).currentVersion(0L)
                .content(objectMapper.writeValueAsString(emptyTree))
                .owner(actor).visibility(DocumentVisibility.PRIVATE).build();
    }

    // ---- enqueue + scheduling ----

    @Test
    void enqueue_addsOpToQueue_andSchedulesDrain() throws Exception {
        stubHappyPath();
        PendingOperation op = pendingOp(UUID.randomUUID(), 0L);

        batcher.enqueue(op);

        // Drain fires within the 5ms window + tolerance (2s accommodates JVM warmup on first run)
        await().atMost(2000, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(committer, atLeastOnce()).commitBatch(
                                eq(documentId), anyLong(), anyLong(), anyString(), any(), anyList()));
    }

    @Test
    void enqueue_secondOp_beforeDrainFires_doesNotScheduleSecondDrain() throws Exception {
        stubHappyPath();
        PendingOperation op1 = pendingOp(UUID.randomUUID(), 0L);
        PendingOperation op2 = pendingOp(UUID.randomUUID(), 0L);

        batcher.enqueue(op1);
        batcher.enqueue(op2); // arrives before drain fires

        // Single drain covers both (2s accommodates JVM warmup on first run)
        await().atMost(2000, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(committer, times(1)).commitBatch(
                                eq(documentId), anyLong(), anyLong(), anyString(), any(), anyList()));
    }

    @Test
    void drain_calledDirectly_processesQueuedOps() throws Exception {
        stubHappyPath();
        batcher.enqueue(pendingOp(UUID.randomUUID(), 0L));

        batcher.drain(documentId);

        verify(committer).commitBatch(eq(documentId), eq(0L), eq(1L), anyString(), any(), anyList());
    }

    // ---- sequential OT ----

    @Test
    void drain_twoOps_secondTransformsAgainstFirst() throws Exception {
        // op2 has baseVersion=0; op1 is accepted first at serverVersion=1.
        // The transform for op2 must include op1's result in its accumulated ops.
        UUID opId1 = UUID.randomUUID();
        UUID opId2 = UUID.randomUUID();
        JsonNode payload1 = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"a\"}");
        JsonNode payload2 = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"b\"}");
        JsonNode transformed2 = realMapper.readTree("{\"path\":[0],\"offset\":1,\"text\":\"b\"}");

        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of());
        when(treeCache.get(any(), anyLong())).thenReturn(Optional.empty());
        doNothing().when(authorizationService).assertCanWrite(any(), any());
        // First call: transform op2 against op1's accepted result
        when(transformer.transform(
                eq(DocumentOperationType.INSERT_TEXT), eq(payload2),
                eq(DocumentOperationType.INSERT_TEXT), eq(payload1)))
                .thenReturn(Optional.of(transformed2));
        when(committer.commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<BatchedOpData> ops = inv.getArgument(5);
                    return ops.stream().map(op -> DocumentOperation.builder()
                            .operationId(op.operationId()).serverVersion(op.serverVersion())
                            .operationType(op.operationType()).payload("{\"path\":[0]}")
                            .clientSessionId("").document(document).actor(actor)
                            .baseVersion(op.baseVersion()).createdAt(Instant.now()).build()).toList();
                });

        PendingOperation pop1 = new PendingOperation(documentId,
                new SubmitOperationRequest(opId1, 0L, DocumentOperationType.INSERT_TEXT, payload1),
                actor, "s1", "alice");
        PendingOperation pop2 = new PendingOperation(documentId,
                new SubmitOperationRequest(opId2, 0L, DocumentOperationType.INSERT_TEXT, payload2),
                actor, "s1", "alice");

        batcher.enqueue(pop1);
        batcher.enqueue(pop2);
        batcher.drain(documentId);

        // op2 must have been transformed against op1
        verify(transformer).transform(
                eq(DocumentOperationType.INSERT_TEXT), eq(payload2),
                eq(DocumentOperationType.INSERT_TEXT), eq(payload1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(committer).commitBatch(any(), anyLong(), anyLong(), anyString(), any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<BatchedOpData> committed = (List<BatchedOpData>) captor.getValue();
        assertThat(committed).hasSize(2);
        assertThat(committed.get(0).serverVersion()).isEqualTo(1L);
        assertThat(committed.get(1).serverVersion()).isEqualTo(2L);
        assertThat(committed.get(1).payload()).isEqualTo(transformed2);
    }

    // ---- stale-cap ----

    @Test
    void drain_staleOp_deliversResyncRequired_skipsCommit() throws Exception {
        // staleCap=200; document at version 206; baseVersion=0 → lag=206 > 200
        Document staleDoc = Document.builder()
                .id(documentId).currentVersion(206L)
                .content(document.getContent()).owner(actor)
                .visibility(DocumentVisibility.PRIVATE).build();
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(staleDoc));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of());
        when(treeCache.get(any(), anyLong())).thenReturn(Optional.empty());
        doNothing().when(authorizationService).assertCanWrite(any(), any());

        PendingOperation staleOp = new PendingOperation(documentId,
                new SubmitOperationRequest(UUID.randomUUID(), 0L,
                        DocumentOperationType.INSERT_TEXT,
                        realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")),
                actor, "s1", "alice");

        batcher.enqueue(staleOp);
        batcher.drain(documentId);

        verify(committer, never()).commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList());
        verify(messagingTemplate).convertAndSendToUser(
                eq("alice"), eq("/queue/errors." + documentId),
                argThat(r -> r instanceof OperationErrorResponse err
                        && "RESYNC_REQUIRED".equals(err.error())
                        && err.currentServerVersion() == 206L));
        assertThat(meterRegistry.counter("operations.resync_required").count()).isEqualTo(1.0);
    }

    // ---- CAS retry ----

    @Test
    void drain_casMiss_retriesAndEventuallySucceeds() throws Exception {
        stubHappyPath();
        // First two commitBatch calls throw CasMissException; third succeeds
        when(committer.commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList()))
                .thenThrow(new CasMissException())
                .thenThrow(new CasMissException())
                .thenAnswer(inv -> {
                    List<BatchedOpData> ops = inv.getArgument(5);
                    return ops.stream().map(op -> DocumentOperation.builder()
                            .operationId(op.operationId()).serverVersion(op.serverVersion())
                            .operationType(op.operationType()).payload("{\"path\":[0]}")
                            .clientSessionId("").document(document).actor(actor)
                            .baseVersion(op.baseVersion()).createdAt(Instant.now()).build()).toList();
                });

        batcher.enqueue(pendingOp(UUID.randomUUID(), 0L));
        batcher.drain(documentId);

        verify(committer, times(3)).commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList());
        assertThat(meterRegistry.counter("operations.retries", "attempt", "1").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("operations.retries", "attempt", "2").count()).isEqualTo(1.0);
        verify(broadcastService, atLeastOnce()).broadcastAcceptedOperation(eq(documentId), any());
    }

    @Test
    void drain_casExhausted_deliversConflictToAll() throws Exception {
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of());
        when(treeCache.get(any(), anyLong())).thenReturn(Optional.empty());
        doNothing().when(authorizationService).assertCanWrite(any(), any());
        lenient().when(transformer.transform(any(), any(), any(), any()))
                .thenReturn(Optional.of(realMapper.readTree("{\"path\":[0]}")));
        // Always miss — exhausts maxCasRetries=3
        when(committer.commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList()))
                .thenThrow(new CasMissException());

        UUID opId = UUID.randomUUID();
        batcher.enqueue(pendingOp(opId, 0L));
        batcher.drain(documentId);

        verify(messagingTemplate).convertAndSendToUser(
                eq("alice"), eq("/queue/errors." + documentId),
                argThat(r -> r instanceof OperationErrorResponse err
                        && "OPERATION_CONFLICT".equals(err.error())
                        && err.operationId().equals(opId)));
        verify(broadcastService, never()).broadcastAcceptedOperation(any(), any());
    }

    // ---- shutdown ----

    @Test
    void shutdown_drainsNonEmptyQueue_beforeTerminating() throws Exception {
        stubHappyPath();
        batcher.enqueue(pendingOp(UUID.randomUUID(), 0L));

        batcher.shutdown();

        verify(committer, atLeastOnce()).commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList());
    }

    // ---- helpers ----

    private PendingOperation pendingOp(UUID opId, long baseVersion) throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
        return new PendingOperation(documentId,
                new SubmitOperationRequest(opId, baseVersion, DocumentOperationType.INSERT_TEXT, payload),
                actor, "session-1", "alice");
    }

    private void stubHappyPath() throws Exception {
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of());
        when(treeCache.get(any(), anyLong())).thenReturn(Optional.empty());
        doNothing().when(authorizationService).assertCanWrite(any(), any());
        lenient().when(transformer.transform(any(), any(), any(), any()))
                .thenReturn(Optional.of(realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")));
        when(committer.commitBatch(any(), anyLong(), anyLong(), anyString(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<BatchedOpData> ops = inv.getArgument(5);
                    return ops.stream().map(op -> DocumentOperation.builder()
                            .operationId(op.operationId()).serverVersion(op.serverVersion())
                            .operationType(op.operationType())
                            .payload("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")
                            .clientSessionId(op.clientSessionId())
                            .document(document).actor(actor).baseVersion(op.baseVersion())
                            .createdAt(Instant.now()).build()).toList();
                });
    }
}
