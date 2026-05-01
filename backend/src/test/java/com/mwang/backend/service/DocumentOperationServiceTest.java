package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.DocumentTreeCache;
import com.mwang.backend.collaboration.OperationTransformer;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.model.DocumentNode;
import com.mwang.backend.domain.model.DocumentTree;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentOperationServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentOperationRepository operationRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private DocumentAuthorizationService authorizationService;
    @Mock private OperationTransformer transformer;
    @Mock private ObjectMapper objectMapper;
    @Mock private EntityManager entityManager;
    @Mock private DocumentTreeCache treeCache;
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private DocumentOperationServiceImpl service;

    private UUID documentId;
    private UUID operationId;
    private User actor;
    private Document document;
    private SimpMessageHeaderAccessor accessor;
    private ObjectMapper realMapper;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        operationId = UUID.randomUUID();
        actor = User.builder().id(UUID.randomUUID()).username("alice").build();
        document = Document.builder().id(documentId).currentVersion(0L)
                .content("{\"children\":[]}").owner(actor).build();
        realMapper = new ObjectMapper();

        accessor = mock(SimpMessageHeaderAccessor.class);
    }

    private SimpMessageHeaderAccessor accessorWithSession(String sessionId) {
        SimpMessageHeaderAccessor acc = mock(SimpMessageHeaderAccessor.class);
        when(acc.getSessionId()).thenReturn(sessionId);
        return acc;
    }

    @Test
    void submitOperationRejectsUnauthorizedActor() throws Exception {
        // Use valid INSERT_TEXT payload so validation passes and we reach the auth check
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        org.mockito.Mockito.doThrow(new DocumentAccessDeniedException(documentId, actor.getId()))
                .when(authorizationService).assertCanWrite(document, actor);

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload);

        assertThatThrownBy(() -> service.submitOperation(documentId, request, accessor))
                .isInstanceOf(DocumentAccessDeniedException.class);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void submitOperationIsIdempotentForDuplicateOperationId() throws Exception {
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        // assertCanWrite does not throw — actor is authorized

        DocumentOperation existing = DocumentOperation.builder()
                .operationId(operationId).serverVersion(1L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")
                .clientSessionId("sess-1")
                .createdAt(Instant.now())
                .document(document)
                .actor(actor)
                .baseVersion(0L)
                .build();
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.of(existing));
        when(objectMapper.readTree(existing.getPayload()))
                .thenReturn(realMapper.readTree(existing.getPayload()));

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload);

        AcceptedOperationResponse response = service.submitOperation(documentId, request, accessor);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        verify(operationRepository, never()).save(any());
        // Auth checked once (pre-idempotency); idempotency path returns before the pessimistic lock
        verify(authorizationService, times(1)).assertCanWrite(document, actor);
    }

    @Test
    void submitOperationIsIdempotentForDuplicateFoundAfterLock() throws Exception {
        // Simulates the race where the pre-lock idempotency check misses, but the post-lock
        // re-check finds the operation committed by a concurrent request.
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        // Pre-lock check: not found (concurrent request hasn't committed yet)
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty())
                // Post-lock check: found (concurrent request committed while we blocked on lock)
                .thenReturn(Optional.of(DocumentOperation.builder()
                        .operationId(operationId).serverVersion(1L)
                        .operationType(DocumentOperationType.INSERT_TEXT)
                        .payload("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")
                        .clientSessionId("sess-concurrent")
                        .createdAt(Instant.now())
                        .document(document).actor(actor).baseVersion(0L)
                        .build()));
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"))
                .thenReturn(realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"));

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload);

        AcceptedOperationResponse response = service.submitOperation(documentId, request, accessor);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void submitOperationPersistsAndReturnsAcceptedResponse() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor accessorWithSession = accessorWithSession("sess-abc");
        when(currentUserProvider.requireCurrentUser(accessorWithSession)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        // assertCanWrite does not throw — actor is authorized
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId)).thenReturn(Optional.empty());
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(documentId, 0L))
                .thenReturn(List.of());

        // Build a DocumentTree with one node at index 0 so INSERT_TEXT at path [0] succeeds
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);

        AcceptedOperationResponse response = service.submitOperation(documentId, request, accessorWithSession);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        assertThat(response.clientSessionId()).isEqualTo("sess-abc");
        verify(operationRepository).save(any(DocumentOperation.class));
        verify(documentRepository).save(document);
        // Auth checked twice: once pre-idempotency on unlocked doc, once post-lock on locked doc
        verify(authorizationService, times(2)).assertCanWrite(document, actor);
    }

    @Test
    void submitOperationPersistsNoOpWhenTransformResolvesToNoOp() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");

        DocumentOperation interveningOp = DocumentOperation.builder()
                .operationId(UUID.randomUUID())
                .serverVersion(1L)
                .operationType(DocumentOperationType.MERGE_BLOCK)
                .payload("{\"path\":[0]}")
                .baseVersion(0L)
                .document(document)
                .actor(actor)
                .build();

        DocumentTree cachedTree = DocumentTree.builder().children(new java.util.ArrayList<>()).build();
        when(treeCache.get(documentId, 0L)).thenReturn(Optional.of(cachedTree));

        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        // assertCanWrite does not throw — actor is authorized
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId)).thenReturn(Optional.empty());
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(documentId, 0L))
                .thenReturn(List.of(interveningOp));
        when(objectMapper.readTree(interveningOp.getPayload()))
                .thenReturn(realMapper.readTree(interveningOp.getPayload()));
        // Transformer returns empty = incoming op resolves to NO_OP
        when(transformer.transform(any(), any(), any(), any())).thenReturn(Optional.empty());

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);

        AcceptedOperationResponse response = service.submitOperation(documentId, request, accessor);

        // Response must reflect NO_OP type and correct version
        assertThat(response.operationType()).isEqualTo(DocumentOperationType.NO_OP);
        assertThat(response.serverVersion()).isEqualTo(1L);
        // Operation persisted with NO_OP type
        org.mockito.ArgumentCaptor<DocumentOperation> captor =
                org.mockito.ArgumentCaptor.forClass(DocumentOperation.class);
        verify(operationRepository).save(captor.capture());
        assertThat(captor.getValue().getOperationType()).isEqualTo(DocumentOperationType.NO_OP);
        // Document content NOT modified for NO_OP
        assertThat(document.getContent()).isEqualTo("{\"children\":[]}");
        // Cache lifecycle honoured even for NO_OP: put new version before evicting old
        verify(treeCache).put(documentId, 1L, cachedTree);
        verify(treeCache).evict(documentId, 0L);
        // No tree deserialization needed — cache was hit
        verify(objectMapper, never()).readValue(any(String.class), org.mockito.ArgumentMatchers.eq(
                com.mwang.backend.domain.model.DocumentTree.class));
        // Auth checked twice: once pre-idempotency on unlocked doc, once post-lock on locked doc
        verify(authorizationService, times(2)).assertCanWrite(document, actor);
    }

    @Test
    void submitOperation_persistsNewOperation_eligibleForOutboxPoller() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor pubAccessor = mock(SimpMessageHeaderAccessor.class);
        when(pubAccessor.getSessionId()).thenReturn("sess-pub");
        when(currentUserProvider.requireCurrentUser(pubAccessor)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of());
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder()
                .children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);

        AcceptedOperationResponse response = service.submitOperation(documentId, request, pubAccessor);

        // Operation must be persisted so the outbox poller can pick it up
        ArgumentCaptor<DocumentOperation> captor = ArgumentCaptor.forClass(DocumentOperation.class);
        verify(operationRepository).save(captor.capture());
        assertThat(captor.getValue().getOperationId()).isEqualTo(operationId);
        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.documentId()).isEqualTo(documentId);
        assertThat(response.serverVersion()).isEqualTo(1L);
    }

    @Test
    void submitOperation_idempotentDuplicate_doesNotPersistNewOperation() throws Exception {
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.of(DocumentOperation.builder()
                        .operationId(operationId).serverVersion(1L)
                        .operationType(DocumentOperationType.INSERT_TEXT)
                        .payload("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")
                        .clientSessionId("sess-1").createdAt(Instant.now())
                        .document(document).actor(actor).baseVersion(0L).build()));
        when(objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"))
                .thenReturn(realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"));

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload);

        service.submitOperation(documentId, request, accessor);

        // No new operation row must be saved for a duplicate submission
        verify(operationRepository, never()).save(any(DocumentOperation.class));
    }

    @Test
    void submitOperation_documentNotFound_throwsBeforeAuthOrLock() throws Exception {
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT,
                realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"));

        assertThatThrownBy(() -> service.submitOperation(documentId, request, accessor))
                .isInstanceOf(DocumentNotFoundException.class);
        verify(authorizationService, never()).assertCanWrite(any(), any());
        verify(documentRepository, never()).findByIdWithPessimisticLock(any());
    }

    @Test
    void submitOperation_nullOperationType_throwsInvalidOperationException() {
        when(currentUserProvider.requireCurrentUser(any(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, null,
                realMapper.createObjectNode().put("path", "ignored"));

        assertThatThrownBy(() -> service.submitOperation(documentId, request, accessor))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Operation type");
        verify(documentRepository, never()).findById(any());
    }

    @Test
    void submitOperation_recordsHotPathTimers() throws Exception {
        // Arrange — minimal happy path: no intervening ops, INSERT_TEXT
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        DocumentTree emptyTree = new DocumentTree(List.of(
                DocumentNode.builder().type("paragraph").build()));
        String treeJson = mapper.writeValueAsString(emptyTree);
        document.setContent(treeJson);

        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndOperationId(eq(documentId), eq(operationId)))
                .thenReturn(Optional.empty());
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of());
        when(objectMapper.readValue(anyString(), eq(DocumentTree.class))).thenReturn(emptyTree);
        when(objectMapper.writeValueAsString(any())).thenReturn(treeJson);
        when(operationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);

        // Act
        service.submitOperation(documentId, request, accessorWithSession("s1"));

        // Assert — each hot-path timer must have recorded exactly 1 sample
        assertThat(meterRegistry.find("loadDocument").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("lockAcquisition").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("loadInterveningOps").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("otTransformLoop").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("treeApply").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("persistOperation").timer().count()).isEqualTo(1);

        // New placeholder counters must be registered at zero
        assertThat(meterRegistry.find("operations.retries").counter()).isNotNull();
        assertThat(meterRegistry.find("operations.resync_required").counter()).isNotNull();
    }

    @Test
    void submitOperation_recordsPerOpJsonParseTimer_whenInterveningOpsExist() throws Exception {
        // Arrange — one intervening op so the transform loop iterates once
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        DocumentTree emptyTree = new DocumentTree(List.of(
                DocumentNode.builder().type("paragraph").build()));
        String treeJson = mapper.writeValueAsString(emptyTree);
        document.setContent(treeJson);
        document.setCurrentVersion(1L);

        DocumentOperation interveningOp = DocumentOperation.builder()
                .operationId(UUID.randomUUID())
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":5,\"text\":\" world\"}")
                .serverVersion(1L)
                .document(document)
                .actor(actor)
                .build();

        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndOperationId(eq(documentId), eq(operationId)))
                .thenReturn(Optional.empty());
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of(interveningOp));
        when(transformer.transform(any(), any(), any(), any()))
                .thenReturn(Optional.of(payload));
        when(objectMapper.readTree(anyString())).thenReturn(payload);
        when(objectMapper.readValue(anyString(), eq(DocumentTree.class))).thenReturn(emptyTree);
        when(objectMapper.writeValueAsString(any())).thenReturn(treeJson);
        when(operationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);

        // Act
        service.submitOperation(documentId, request, accessorWithSession("s1"));

        // Assert — perOpJsonParse must have recorded 1 sample (one iteration)
        assertThat(meterRegistry.find("perOpJsonParse").timer().count()).isEqualTo(1);
    }

    @Test
    void submitOperationPreParsesInterveningOpsBeforeTransformLoop() throws Exception {
        // Two intervening ops — readTree should be called exactly twice (once per op payload),
        // and never inside the transform loop itself.
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor acc = accessorWithSession("sess-1");

        DocumentOperation interveningA = DocumentOperation.builder()
                .operationId(UUID.randomUUID()).serverVersion(1L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")
                .baseVersion(0L).document(document).actor(actor).build();
        DocumentOperation interveningB = DocumentOperation.builder()
                .operationId(UUID.randomUUID()).serverVersion(2L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":1,\"text\":\"y\"}")
                .baseVersion(1L).document(document).actor(actor).build();

        JsonNode parsedA = realMapper.readTree(interveningA.getPayload());
        JsonNode parsedB = realMapper.readTree(interveningB.getPayload());

        when(currentUserProvider.requireCurrentUser(acc)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of(interveningA, interveningB));
        when(objectMapper.readTree(interveningA.getPayload())).thenReturn(parsedA);
        when(objectMapper.readTree(interveningB.getPayload())).thenReturn(parsedB);
        // Transform: A shifts offset of incoming; B shifts further
        when(transformer.transform(eq(DocumentOperationType.INSERT_TEXT), any(),
                eq(DocumentOperationType.INSERT_TEXT), eq(parsedA))).thenReturn(Optional.of(payload));
        when(transformer.transform(eq(DocumentOperationType.INSERT_TEXT), any(),
                eq(DocumentOperationType.INSERT_TEXT), eq(parsedB))).thenReturn(Optional.of(payload));
        // Tree apply
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);
        service.submitOperation(documentId, request, acc);

        // readTree called exactly once per intervening op payload — in the pre-parse step
        verify(objectMapper, times(1)).readTree(interveningA.getPayload());
        verify(objectMapper, times(1)).readTree(interveningB.getPayload());
    }

    @Test
    void submitOperationUsesTreeCacheHitAndSkipsReadValue() throws Exception {
        // Arrange: treeCache.get() returns a pre-built tree copy — readValue should NOT be called
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor acc = accessorWithSession("sess-2");

        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree cachedTree = DocumentTree.builder()
                .children(new java.util.ArrayList<>(List.of(node))).build();

        when(currentUserProvider.requireCurrentUser(acc)).thenReturn(actor);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of());
        // Cache hit for version 0
        when(treeCache.get(documentId, 0L)).thenReturn(Optional.of(cachedTree));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);
        service.submitOperation(documentId, request, acc);

        // readValue must NOT be called — the cache provided the tree
        verify(objectMapper, never()).readValue(anyString(), eq(DocumentTree.class));
        // Cache is updated: put at nextVersion=1, evict at currentVersion=0
        verify(treeCache).put(eq(documentId), eq(1L), any(DocumentTree.class));
        verify(treeCache).evict(documentId, 0L);
    }

    @Test
    void serviceConstructor_registersRetriesAndResyncCounters_inPrometheusFormat() {
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new DocumentOperationServiceImpl(
                mock(DocumentRepository.class),
                mock(DocumentOperationRepository.class),
                mock(CurrentUserProvider.class),
                mock(DocumentAuthorizationService.class),
                mock(OperationTransformer.class),
                mock(ObjectMapper.class),
                mock(EntityManager.class),
                prometheusRegistry,
                mock(DocumentTreeCache.class));

        String scrape = prometheusRegistry.scrape();
        assertThat(scrape).contains("operations_retries_total");
        assertThat(scrape).contains("operations_resync_required_total");
    }
}
