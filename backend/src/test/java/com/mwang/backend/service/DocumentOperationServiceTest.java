package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    @InjectMocks
    private DocumentOperationServiceImpl service;

    private UUID documentId;
    private UUID operationId;
    private User actor;
    private Document document;
    private Map<String, Object> sessionAttributes;
    private ObjectMapper realMapper;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        operationId = UUID.randomUUID();
        actor = User.builder().id(UUID.randomUUID()).username("alice").build();
        document = Document.builder().id(documentId).currentVersion(0L)
                .content("{\"children\":[]}").owner(actor).build();
        sessionAttributes = Map.of("userId", actor.getId().toString());
        realMapper = new ObjectMapper();
    }

    @Test
    void submitOperationRejectsUnauthorizedActor() throws Exception {
        // Use valid INSERT_TEXT payload so validation passes and we reach the auth check
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(sessionAttributes)).thenReturn(actor);
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        org.mockito.Mockito.doThrow(new DocumentAccessDeniedException(documentId, actor.getId()))
                .when(authorizationService).assertCanWrite(document, actor);

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload, null);

        assertThatThrownBy(() -> service.submitOperation(documentId, request, sessionAttributes))
                .isInstanceOf(DocumentAccessDeniedException.class);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void submitOperationIsIdempotentForDuplicateOperationId() throws Exception {
        JsonNode validPayload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(sessionAttributes)).thenReturn(actor);

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
                operationId, 0L, DocumentOperationType.INSERT_TEXT, validPayload, null);

        AcceptedOperationResponse response = service.submitOperation(documentId, request, sessionAttributes);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void submitOperationPersistsAndReturnsAcceptedResponse() throws Exception {
        JsonNode payload = realMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        when(currentUserProvider.requireCurrentUser(sessionAttributes)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId)).thenReturn(Optional.empty());
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        // assertCanWrite does not throw — actor is authorized
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(documentId, 0L))
                .thenReturn(List.of());

        // Build a DocumentTree with one node at index 0 so INSERT_TEXT at path [0] succeeds
        DocumentNode node = DocumentNode.builder().type("paragraph").text("").build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(node))).build();
        when(objectMapper.readValue(document.getContent(), DocumentTree.class)).thenReturn(tree);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"children\":[]}");

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload, "sess-abc");

        AcceptedOperationResponse response = service.submitOperation(documentId, request, sessionAttributes);

        assertThat(response.operationId()).isEqualTo(operationId);
        assertThat(response.serverVersion()).isEqualTo(1L);
        assertThat(response.clientSessionId()).isEqualTo("sess-abc");
        verify(operationRepository).save(any(DocumentOperation.class));
        verify(documentRepository).save(document);
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

        when(currentUserProvider.requireCurrentUser(sessionAttributes)).thenReturn(actor);
        when(operationRepository.findByDocumentIdAndOperationId(documentId, operationId)).thenReturn(Optional.empty());
        when(documentRepository.findByIdWithPessimisticLock(documentId)).thenReturn(Optional.of(document));
        // assertCanWrite does not throw
        when(operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(documentId, 0L))
                .thenReturn(List.of(interveningOp));
        when(objectMapper.readTree(interveningOp.getPayload()))
                .thenReturn(realMapper.readTree(interveningOp.getPayload()));
        // Transformer returns empty = incoming op resolves to NO_OP
        when(transformer.transform(any(), any(), any(), any())).thenReturn(Optional.empty());

        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload, "sess-abc");

        AcceptedOperationResponse response = service.submitOperation(documentId, request, sessionAttributes);

        // Response must reflect NO_OP type and correct version
        assertThat(response.operationType()).isEqualTo(DocumentOperationType.NO_OP);
        assertThat(response.serverVersion()).isEqualTo(1L);
        // Operation persisted with NO_OP type
        org.mockito.ArgumentCaptor<DocumentOperation> captor =
                org.mockito.ArgumentCaptor.forClass(DocumentOperation.class);
        verify(operationRepository).save(captor.capture());
        assertThat(captor.getValue().getOperationType()).isEqualTo(DocumentOperationType.NO_OP);
        // Document content NOT modified — objectMapper.readValue should never be called for tree deserialization
        verify(objectMapper, never()).readValue(any(String.class), org.mockito.ArgumentMatchers.eq(
                com.mwang.backend.domain.model.DocumentTree.class));
    }
}
