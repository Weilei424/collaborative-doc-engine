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
import com.mwang.backend.service.exception.OperationConflictException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Mock private DocumentTreeCache treeCache;
    @Spy  private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DocumentOperationServiceImpl service;

    private UUID documentId;
    private UUID operationId;
    private User actor;
    private Document document;
    private SimpMessageHeaderAccessor accessor;
    private final ObjectMapper realMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        documentId  = UUID.randomUUID();
        operationId = UUID.randomUUID();
        actor    = User.builder().id(UUID.randomUUID()).username("alice").build();
        document = Document.builder().id(documentId).currentVersion(0L)
                .content("{\"children\":[]}").owner(actor).build();
        accessor = mock(SimpMessageHeaderAccessor.class);

        DocumentOperationCommitter committer = new DocumentOperationCommitter(
                documentRepository, operationRepository, objectMapper);
        service = new DocumentOperationServiceImpl(
                documentRepository, operationRepository,
                currentUserProvider, authorizationService,
                transformer, objectMapper,
                meterRegistry, treeCache, committer, 5);
    }

    private SimpMessageHeaderAccessor accessorWithSession(String sessionId) {
        SimpMessageHeaderAccessor acc = mock(SimpMessageHeaderAccessor.class);
        when(acc.getSessionId()).thenReturn(sessionId);
        return acc;
    }

    private DocumentOperation buildAccepted(long serverVersion) {
        return DocumentOperation.builder()
                .operationId(operationId).serverVersion(serverVersion)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")
                .clientSessionId("sess-1").createdAt(Instant.now())
                .document(document).actor(actor).baseVersion(0L).build();
    }

    // ---- pre-loop idempotency ----

    @Test
    void preLoopIdempotencyCheck_authorizedActor_returnsExistingResponse_withoutPersisting() throws Exception {
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.of(buildAccepted(1L)));
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"))
                .thenReturn(realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"));

        AcceptedOperationResponse response = service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload),
                accessor);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        verify(operationRepository, never()).save(any());
        verify(authorizationService).assertCanWrite(document, actor);
    }

    @Test
    void preLoopIdempotencyCheck_revokedActor_throwsAccessDenied() throws Exception {
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.of(buildAccepted(1L)));
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        org.mockito.Mockito.doThrow(new DocumentAccessDeniedException(documentId, actor.getId()))
                .when(authorizationService).assertCanWrite(document, actor);

        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload),
                accessor))
                .isInstanceOf(DocumentAccessDeniedException.class);
        verify(operationRepository, never()).save(any());
    }

    // ---- validation ----

    @Test
    void nullOperationType_throwsInvalidOperationException_beforeAnyDb() {
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, null, realMapper.createObjectNode().put("path", "ignored"));

        assertThatThrownBy(() -> service.submitOperation(documentId, request, accessor))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Operation type");
        verify(documentRepository, never()).findById(any());
    }

    @Test
    void documentNotFound_throwsDocumentNotFoundException() throws Exception {
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT,
                        realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")),
                accessor))
                .isInstanceOf(DocumentNotFoundException.class);
        verify(authorizationService, never()).assertCanWrite(any(), any());
    }

    @Test
    void unauthorizedActor_throwsDocumentAccessDeniedException() throws Exception {
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        org.mockito.Mockito.doThrow(new DocumentAccessDeniedException(documentId, actor.getId()))
                .when(authorizationService).assertCanWrite(document, actor);

        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload),
                accessor))
                .isInstanceOf(DocumentAccessDeniedException.class);
        verify(operationRepository, never()).save(any());
    }

    // ---- happy path ----

    @Test
    void happyPath_casSucceeds_firstAttempt_returnsAcceptedResponse() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor acc = accessorWithSession("sess-abc");
        when(currentUserProvider.requireCurrentUser(acc)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of());
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");
        when(documentRepository.tryAdvanceVersion(eq(documentId), eq(0L), eq(1L), anyString()))
                .thenReturn(1);
        when(operationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            DocumentOperation op = inv.getArgument(0);
            return DocumentOperation.builder()
                    .operationId(op.getOperationId()).serverVersion(op.getServerVersion())
                    .operationType(op.getOperationType()).payload(op.getPayload())
                    .clientSessionId(op.getClientSessionId()).createdAt(Instant.now())
                    .document(document).actor(actor).baseVersion(0L).build();
        });

        AcceptedOperationResponse response = service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                acc);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        assertThat(response.clientSessionId()).isEqualTo("sess-abc");
        verify(operationRepository).saveAndFlush(any(DocumentOperation.class));
        verify(documentRepository, never()).save(any());
    }

    // ---- retry on CAS miss ----

    @Test
    void casMiss_onFirstAttempt_retrySucceeds_andIncrementsRetriesCounter() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor acc = accessorWithSession("sess-r");
        when(currentUserProvider.requireCurrentUser(acc)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        // Both read-snapshot calls return the same document (second attempt re-reads)
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of());
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");
        // Attempt 1: CAS miss; attempt 2: CAS success
        when(documentRepository.tryAdvanceVersion(eq(documentId), eq(0L), eq(1L), anyString()))
                .thenReturn(0)   // miss
                .thenReturn(1);  // hit
        when(operationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            DocumentOperation op = inv.getArgument(0);
            return DocumentOperation.builder()
                    .operationId(op.getOperationId()).serverVersion(op.getServerVersion())
                    .operationType(op.getOperationType()).payload(op.getPayload())
                    .clientSessionId(op.getClientSessionId()).createdAt(Instant.now())
                    .document(document).actor(actor).baseVersion(0L).build();
        });

        AcceptedOperationResponse response = service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                acc);

        assertThat(response.serverVersion()).isEqualTo(1L);
        assertThat(meterRegistry.find("operations.retries").tag("attempt", "1").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void casMiss_exhaustsMaxAttempts_throwsOperationConflictException() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of());
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");
        // Always miss
        when(documentRepository.tryAdvanceVersion(any(), anyLong(), anyLong(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor))
                .isInstanceOf(OperationConflictException.class);
        // retries counter incremented for all 5 attempts
        for (int i = 1; i <= 5; i++) {
            assertThat(meterRegistry.find("operations.retries").tag("attempt", String.valueOf(i)).counter().count())
                    .isEqualTo(1.0);
        }
    }

    // ---- in-loop idempotency re-check ----

    @Test
    void inLoopIdempotencyRecheck_firesOnRetry_noRetryCounterIncrement() throws Exception {
        // Simulates: pre-loop check missed (empty); on retry the re-read includes the already-accepted op
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        // Pre-loop: not found
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        // First read snapshot: no intervening ops (pre-loop didn't find it yet)
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        DocumentOperation alreadyAccepted = buildAccepted(1L);
        // First attempt: CAS returns 0 (miss)
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");
        when(documentRepository.tryAdvanceVersion(any(), anyLong(), anyLong(), anyString())).thenReturn(0);
        // Second read snapshot: the accepted op is now in the intervening ops list
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L))
                .thenReturn(List.of())       // attempt 1
                .thenReturn(List.of(alreadyAccepted)); // attempt 2 — op now visible
        when(objectMapper.readTree(alreadyAccepted.getPayload()))
                .thenReturn(realMapper.readTree(alreadyAccepted.getPayload()));

        AcceptedOperationResponse response = service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        verify(operationRepository, never()).save(any());
        // Only attempt=1 retry counter should be incremented (the miss before we detected idempotency)
        assertThat(meterRegistry.find("operations.retries").tag("attempt", "1").counter().count())
                .isEqualTo(1.0);
        // No attempt=2 counter — we returned idempotent before reaching tryAdvanceVersion again
        assertThat(meterRegistry.find("operations.retries").tag("attempt", "2").counter()).isNull();
    }

    // ---- constraint-violation idempotent safety net ----

    @Test
    void insertConstraintViolation_treatedAsIdempotentSuccess() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of());
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");
        when(documentRepository.tryAdvanceVersion(any(), anyLong(), anyLong(), anyString())).thenReturn(1);
        // INSERT throws (document_id, operation_id) unique constraint violation
        when(operationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_document_operations_document_operation"));
        // The service then queries by operationId to return the already-accepted row
        DocumentOperation accepted = buildAccepted(1L);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty())   // pre-loop: not found
                .thenReturn(Optional.of(accepted)); // safety-net lookup after violation
        when(objectMapper.readTree(accepted.getPayload()))
                .thenReturn(realMapper.readTree(accepted.getPayload()));

        AcceptedOperationResponse response = service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        // No retry counter incremented — constraint violation is not a CAS miss
        assertThat(meterRegistry.find("operations.retries").tag("attempt", "1").counter()).isNull();
    }

    // ---- ACL re-check on retry ----

    @Test
    void aclRejectedOnRetry_throwsAccessDenied() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        // Attempt 1: CAS miss
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of());
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");
        when(documentRepository.tryAdvanceVersion(any(), anyLong(), anyLong(), anyString())).thenReturn(0);
        // Attempt 2: ACL revoked (authorizationService throws on second call)
        org.mockito.Mockito.doNothing()
                .doThrow(new DocumentAccessDeniedException(documentId, actor.getId()))
                .when(authorizationService).assertCanWrite(any(), any());

        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor))
                .isInstanceOf(DocumentAccessDeniedException.class);
    }

    // ---- NO_OP path ----

    @Test
    void transformResolvesToNoOp_persistsNoOpAndUpdatesCacheLifecycle() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        DocumentOperation interveningOp = DocumentOperation.builder()
                .operationId(UUID.randomUUID()).serverVersion(1L)
                .operationType(DocumentOperationType.MERGE_BLOCK).payload("{\"path\":[0]}")
                .baseVersion(0L).document(document).actor(actor).build();
        DocumentTree cachedTree = DocumentTree.builder().children(new java.util.ArrayList<>()).build();
        when(treeCache.get(documentId, 0L)).thenReturn(Optional.of(cachedTree));
        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                documentId, 0L)).thenReturn(List.of(interveningOp));
        when(objectMapper.readTree(interveningOp.getPayload()))
                .thenReturn(realMapper.readTree(interveningOp.getPayload()));
        when(transformer.transform(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");
        when(documentRepository.tryAdvanceVersion(any(), anyLong(), anyLong(), anyString())).thenReturn(1);
        when(operationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            DocumentOperation op = inv.getArgument(0);
            return DocumentOperation.builder()
                    .operationId(op.getOperationId()).serverVersion(op.getServerVersion())
                    .operationType(op.getOperationType()).payload(op.getPayload())
                    .clientSessionId(op.getClientSessionId()).createdAt(Instant.now())
                    .document(document).actor(actor).baseVersion(0L).build();
        });

        AcceptedOperationResponse response = service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor);

        assertThat(response.operationType()).isEqualTo(DocumentOperationType.NO_OP);
        assertThat(response.serverVersion()).isEqualTo(1L);
        org.mockito.ArgumentCaptor<DocumentOperation> captor =
                org.mockito.ArgumentCaptor.forClass(DocumentOperation.class);
        verify(operationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOperationType()).isEqualTo(DocumentOperationType.NO_OP);
        assertThat(document.getContent()).isEqualTo("{\"children\":[]}");
        verify(treeCache).put(documentId, 1L, cachedTree);
        verify(treeCache).evict(documentId, 0L);
        verify(objectMapper, never()).readValue(anyString(), eq(DocumentTree.class));
    }

    // ---- metrics ----

    @Test
    void hotPathTimers_allRecord_lockAcquisitionAbsent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        DocumentTree emptyTree = new DocumentTree(List.of(DocumentNode.builder().type("paragraph").build()));
        String treeJson = mapper.writeValueAsString(emptyTree);
        document.setContent(treeJson);

        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(eq(documentId), eq(operationId)))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of());
        when(objectMapper.readValue(anyString(), eq(DocumentTree.class))).thenReturn(emptyTree);
        when(objectMapper.writeValueAsString(any())).thenReturn(treeJson);
        when(documentRepository.tryAdvanceVersion(any(), anyLong(), anyLong(), anyString())).thenReturn(1);
        when(operationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            DocumentOperation op = inv.getArgument(0);
            return DocumentOperation.builder()
                    .operationId(op.getOperationId()).serverVersion(op.getServerVersion())
                    .operationType(op.getOperationType()).payload(op.getPayload())
                    .clientSessionId(op.getClientSessionId()).createdAt(Instant.now())
                    .document(document).actor(actor).baseVersion(0L).build();
        });

        service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessorWithSession("s1"));

        assertThat(meterRegistry.find("loadDocument").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("loadInterveningOps").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("otTransformLoop").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("treeApply").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("persistOperation").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("lockAcquisition").timer()).isNull();
    }

    @Test
    void perOpJsonParseTimer_recordsOneSample_whenInterveningOpsExist() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        DocumentTree emptyTree = new DocumentTree(List.of(DocumentNode.builder().type("paragraph").build()));
        String treeJson = mapper.writeValueAsString(emptyTree);
        document.setContent(treeJson);
        document.setCurrentVersion(1L);
        DocumentOperation interveningOp = DocumentOperation.builder()
                .operationId(UUID.randomUUID()).operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":5,\"text\":\" world\"}").serverVersion(1L)
                .document(document).actor(actor).build();

        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(eq(documentId), eq(operationId)))
                .thenReturn(Optional.empty());
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                eq(documentId), anyLong())).thenReturn(List.of(interveningOp));
        when(transformer.transform(any(), any(), any(), any())).thenReturn(Optional.of(payload));
        when(objectMapper.readTree(anyString())).thenReturn(payload);
        when(objectMapper.readValue(anyString(), eq(DocumentTree.class))).thenReturn(emptyTree);
        when(objectMapper.writeValueAsString(any())).thenReturn(treeJson);
        when(documentRepository.tryAdvanceVersion(any(), anyLong(), anyLong(), anyString())).thenReturn(1);
        when(operationRepository.saveAndFlush(any())).thenAnswer(inv -> {
            DocumentOperation op = inv.getArgument(0);
            return DocumentOperation.builder()
                    .operationId(op.getOperationId()).serverVersion(op.getServerVersion())
                    .operationType(op.getOperationType()).payload(op.getPayload())
                    .clientSessionId("").createdAt(Instant.now())
                    .document(document).actor(actor).baseVersion(0L).build();
        });

        service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessorWithSession("s1"));

        assertThat(meterRegistry.find("perOpJsonParse").timer().count()).isEqualTo(1);
    }

    @Test
    void constructor_registersRetriesAndResyncCounters_inPrometheusFormat() {
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        DocumentRepository mockRepo = mock(DocumentRepository.class);
        DocumentOperationRepository mockOpRepo = mock(DocumentOperationRepository.class);
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        DocumentOperationCommitter prometheusCommitter = new DocumentOperationCommitter(
                mockRepo, mockOpRepo, mockMapper);
        new DocumentOperationServiceImpl(
                mockRepo, mockOpRepo,
                mock(CurrentUserProvider.class), mock(DocumentAuthorizationService.class),
                mock(OperationTransformer.class), mockMapper,
                prometheusRegistry, mock(DocumentTreeCache.class), prometheusCommitter, 5);

        String scrape = prometheusRegistry.scrape();
        assertThat(scrape).doesNotContain("lockAcquisition");
        assertThat(scrape).contains("operations_resync_required_total");
    }
}
