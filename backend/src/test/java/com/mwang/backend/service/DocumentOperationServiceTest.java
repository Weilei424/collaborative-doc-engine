package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentOperationServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentOperationRepository operationRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private DocumentAuthorizationService authorizationService;
    @Mock private DocumentOperationBatcher batcher;
    @Mock private CollaborationBroadcastService broadcastService;
    @Mock private RedisCollaborationEventPublisher redisPublisher;
    @Mock private SimpMessageHeaderAccessor accessor;

    private DocumentOperationServiceImpl service;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID documentId;
    private UUID operationId;
    private User actor;
    private Document document;

    @BeforeEach
    void setUp() {
        documentId  = UUID.randomUUID();
        operationId = UUID.randomUUID();
        actor = User.builder().id(UUID.randomUUID()).username("alice").build();
        document = Document.builder().id(documentId).currentVersion(0L)
                .content("{\"children\":[]}").owner(actor).build();
        service = new DocumentOperationServiceImpl(
                documentRepository, operationRepository,
                currentUserProvider, authorizationService,
                batcher, broadcastService, redisPublisher,
                mapper, new SimpleMeterRegistry());
    }

    // ---- validation ----

    @Test
    void submitOperation_nullType_throwsInvalidOperation() throws Exception {
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, null, payload), accessor))
                .isInstanceOf(InvalidOperationException.class);
        verify(batcher, never()).enqueue(any());
    }

    @Test
    void submitOperation_noOpType_throwsInvalidOperation() throws Exception {
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.NO_OP, payload), accessor))
                .isInstanceOf(InvalidOperationException.class);
        verify(batcher, never()).enqueue(any());
    }

    @Test
    void submitOperation_missingPath_throwsInvalidOperation() throws Exception {
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        JsonNode payload = mapper.readTree("{\"offset\":0,\"text\":\"hi\"}");
        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload), accessor))
                .isInstanceOf(InvalidOperationException.class);
        verify(batcher, never()).enqueue(any());
    }

    @Test
    void submitOperation_insertText_missingOffset_throwsInvalidOperation() throws Exception {
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        JsonNode payload = mapper.readTree("{\"path\":[0],\"text\":\"hi\"}");
        assertThatThrownBy(() -> service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload), accessor))
                .isInstanceOf(InvalidOperationException.class);
    }

    // ---- idempotency fast path ----

    @Test
    void submitOperation_idempotent_broadcastsPriorOpAndDoesNotEnqueue() throws Exception {
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        DocumentOperation priorOp = DocumentOperation.builder()
                .operationId(operationId).serverVersion(3L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")
                .clientSessionId("s1").document(document).actor(actor)
                .createdAt(Instant.now()).baseVersion(0L).build();

        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.of(priorOp));
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(authorizationService).assertCanWrite(any(), any());

        service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor);

        verify(broadcastService).broadcastAcceptedOperation(eq(documentId),
                argThat(r -> r.serverVersion() == 3L && r.operationId().equals(operationId)));
        verify(redisPublisher).publishAcceptedOperation(eq(documentId), any());
        verify(batcher, never()).enqueue(any());
    }

    // ---- enqueue delegation ----

    @Test
    void submitOperation_newOp_enqueuesToBatcher() throws Exception {
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId))
                .thenReturn(Optional.empty());
        when(accessor.getSessionId()).thenReturn("session-abc");
        when(accessor.getUser()).thenReturn(() -> "alice");

        service.submitOperation(documentId,
                new SubmitOperationRequest(operationId, 0L, DocumentOperationType.INSERT_TEXT, payload),
                accessor);

        ArgumentCaptor<PendingOperation> captor = ArgumentCaptor.forClass(PendingOperation.class);
        verify(batcher).enqueue(captor.capture());
        PendingOperation queued = captor.getValue();
        assertThat(queued.documentId()).isEqualTo(documentId);
        assertThat(queued.request().operationId()).isEqualTo(operationId);
        assertThat(queued.actor()).isEqualTo(actor);
        assertThat(queued.clientSessionId()).isEqualTo("session-abc");
        assertThat(queued.principalName()).isEqualTo("alice");
    }
}
