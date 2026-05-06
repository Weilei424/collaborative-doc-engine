package com.mwang.backend.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.service.CollaborationPresenceService;
import com.mwang.backend.service.CollaborationSessionService;
import com.mwang.backend.service.DocumentOperationService;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.service.exception.OperationConflictException;
import com.mwang.backend.web.model.SubmitOperationRequest;
import com.mwang.backend.domain.DocumentOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CollaborationControllerOperationTest {

    private CollaborationController controller;
    private DocumentOperationService operationService;
    private CollaborationBroadcastService broadcastService;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        operationService = mock(DocumentOperationService.class);
        broadcastService = mock(CollaborationBroadcastService.class);
        controller = new CollaborationController(
                mock(CollaborationSessionService.class),
                mock(CollaborationPresenceService.class),
                broadcastService,
                operationService);
        mapper = new ObjectMapper();
    }

    @Test
    void submitOperationDelegatesToService() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        controller.submitOperation(documentId, request, accessor);

        verify(operationService).submitOperation(eq(documentId), eq(request), any());
        verifyNoInteractions(broadcastService);
    }

    @Test
    void submitOperationPropagatesAccessDenied() throws Exception {
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
        SubmitOperationRequest request = new SubmitOperationRequest(
                UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        doThrow(new DocumentAccessDeniedException(documentId, UUID.randomUUID()))
                .when(operationService).submitOperation(eq(documentId), eq(request), any());

        assertThatThrownBy(() -> controller.submitOperation(documentId, request, accessor))
                .isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(broadcastService);
    }

    @Test
    void submitOperationPropagatesInvalidOperation() throws Exception {
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mapper.createObjectNode();
        SubmitOperationRequest request = new SubmitOperationRequest(
                UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        doThrow(new InvalidOperationException("invalid payload"))
                .when(operationService).submitOperation(eq(documentId), eq(request), any());

        assertThatThrownBy(() -> controller.submitOperation(documentId, request, accessor))
                .isInstanceOf(InvalidOperationException.class);
        verifyNoInteractions(broadcastService);
    }

    @Test
    void submitOperationPropagatesOperationConflict() throws Exception {
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
        SubmitOperationRequest request = new SubmitOperationRequest(
                UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        doThrow(new OperationConflictException("conflict"))
                .when(operationService).submitOperation(eq(documentId), eq(request), any());

        assertThatThrownBy(() -> controller.submitOperation(documentId, request, accessor))
                .isInstanceOf(OperationConflictException.class);
        verifyNoInteractions(broadcastService);
    }
}
