package com.mwang.backend.web.controller;

import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.service.CollaborationPresenceService;
import com.mwang.backend.service.CollaborationSessionService;
import com.mwang.backend.service.DocumentOperationService;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.LeaveSessionRequest;
import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceType;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import com.mwang.backend.web.model.SubmitOperationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CollaborationControllerTest {

    private CollaborationController collaborationController;
    private CollaborationSessionService sessionService;
    private CollaborationPresenceService presenceService;
    private CollaborationBroadcastService broadcastService;
    private DocumentOperationService documentOperationService;
    private RedisCollaborationEventPublisher redisPublisher;

    @BeforeEach
    void setUp() {
        sessionService = mock(CollaborationSessionService.class);
        presenceService = mock(CollaborationPresenceService.class);
        broadcastService = mock(CollaborationBroadcastService.class);
        documentOperationService = mock(DocumentOperationService.class);
        redisPublisher = mock(RedisCollaborationEventPublisher.class);
        collaborationController = new CollaborationController(
                sessionService, presenceService, broadcastService, documentOperationService, redisPublisher);
    }

    @Test
    void joinSessionDelegatesToSessionServiceAndBroadcastsSessionSnapshot() {
        UUID documentId = UUID.randomUUID();
        CollaborationSessionSnapshot snapshot = snapshot(documentId);
        StompHeaderAccessor headerAccessor = accessor();

        when(sessionService.join(documentId, headerAccessor.getSessionAttributes())).thenReturn(snapshot);

        collaborationController.joinSession(documentId, headerAccessor);

        verify(sessionService).join(documentId, headerAccessor.getSessionAttributes());
        verify(broadcastService).broadcastSessionSnapshot(documentId, snapshot);
        verifyNoMoreInteractions(broadcastService);
    }

    @Test
    void leaveSessionDelegatesToSessionServiceAndBroadcastsSessionSnapshot() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        CollaborationSessionSnapshot snapshot = snapshot(documentId);
        StompHeaderAccessor headerAccessor = accessor();

        when(sessionService.leave(documentId, sessionId, headerAccessor.getSessionAttributes())).thenReturn(snapshot);

        collaborationController.leaveSession(documentId, new LeaveSessionRequest(sessionId), headerAccessor);

        verify(sessionService).leave(documentId, sessionId, headerAccessor.getSessionAttributes());
        verify(broadcastService).broadcastSessionSnapshot(documentId, snapshot);
        verifyNoMoreInteractions(broadcastService);
    }

    @Test
    void leaveSessionRejectsMissingPayloadDeterministically() {
        UUID documentId = UUID.randomUUID();

        assertThatThrownBy(() -> collaborationController.leaveSession(documentId, null, accessor()))
                .isInstanceOf(InvalidCollaborationRequestException.class)
                .hasMessage("Leave session requires sessionId");

        verifyNoInteractions(sessionService, broadcastService);
    }

    @Test
    void leaveSessionRejectsMissingSessionIdDeterministically() {
        UUID documentId = UUID.randomUUID();

        assertThatThrownBy(() -> collaborationController.leaveSession(documentId, new LeaveSessionRequest(null), accessor()))
                .isInstanceOf(InvalidCollaborationRequestException.class)
                .hasMessage("Leave session requires sessionId");

        verifyNoInteractions(sessionService, broadcastService);
    }

    @Test
    void updatePresenceDelegatesToPresenceServiceAndBroadcastsPresenceEvent() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.TYPING, Map.of("typing", true));
        PresenceEventResponse event = new PresenceEventResponse(
                documentId,
                sessionId,
                UUID.randomUUID(),
                "tester",
                PresenceType.TYPING,
                Map.of("typing", true),
                Instant.parse("2026-03-23T12:00:00Z")
        );
        StompHeaderAccessor headerAccessor = accessor();

        when(presenceService.publishPresence(documentId, request, headerAccessor.getSessionAttributes())).thenReturn(event);

        collaborationController.updatePresence(documentId, request, headerAccessor);

        verify(presenceService).publishPresence(documentId, request, headerAccessor.getSessionAttributes());
        verify(broadcastService).broadcastPresenceEvent(documentId, event);
        verifyNoMoreInteractions(broadcastService);
    }

    @Test
    void submitOperationBroadcastsLocallyAndPublishesToRedis() {
        UUID documentId = UUID.randomUUID();
        AcceptedOperationResponse response = new AcceptedOperationResponse(
                UUID.randomUUID(), documentId, 1L,
                DocumentOperationType.INSERT_TEXT, null,
                UUID.randomUUID(), "session-1", Instant.now()
        );
        SubmitOperationRequest request = new SubmitOperationRequest(
                UUID.randomUUID(), 0L,
                DocumentOperationType.INSERT_TEXT, null
        );
        StompHeaderAccessor headerAccessor = accessor();
        when(documentOperationService.submitOperation(eq(documentId), eq(request), any())).thenReturn(response);

        collaborationController.submitOperation(documentId, request, headerAccessor);

        verify(broadcastService).broadcastAcceptedOperation(documentId, response);
        verify(redisPublisher).publishAcceptedOperation(documentId, response);
    }

    private StompHeaderAccessor accessor() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new HashMap<>());
        return accessor;
    }

    private CollaborationSessionSnapshot snapshot(UUID documentId) {
        return new CollaborationSessionSnapshot(
                documentId,
                List.of(new CollaborationSessionResponse(
                        UUID.randomUUID(),
                        documentId,
                        UUID.randomUUID(),
                        "tester",
                        Instant.parse("2026-03-23T11:59:00Z"),
                        Instant.parse("2026-03-23T12:00:00Z")
                ))
        );
    }
}
