package com.mwang.backend.web.controller;

import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.service.CollaborationPresenceService;
import com.mwang.backend.service.CollaborationSessionService;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.JoinSessionRequest;
import com.mwang.backend.web.model.LeaveSessionRequest;
import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceType;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CollaborationControllerTest {

    private CollaborationController collaborationController;
    private CollaborationSessionService sessionService;
    private CollaborationPresenceService presenceService;
    private CollaborationBroadcastService broadcastService;

    @BeforeEach
    void setUp() {
        sessionService = mock(CollaborationSessionService.class);
        presenceService = mock(CollaborationPresenceService.class);
        broadcastService = mock(CollaborationBroadcastService.class);
        collaborationController = new CollaborationController(sessionService, presenceService, broadcastService);
    }

    @Test
    void joinSessionDelegatesToSessionServiceAndBroadcastsSessionSnapshot() {
        UUID documentId = UUID.randomUUID();
        UUID clientSessionHint = UUID.randomUUID();
        CollaborationSessionSnapshot snapshot = snapshot(documentId);

        when(sessionService.join(documentId, clientSessionHint)).thenReturn(snapshot);

        collaborationController.joinSession(documentId, new JoinSessionRequest(clientSessionHint));

        verify(sessionService).join(documentId, clientSessionHint);
        verify(broadcastService).broadcastSessionSnapshot(documentId, snapshot);
        verifyNoMoreInteractions(broadcastService);
    }

    @Test
    void leaveSessionDelegatesToSessionServiceAndBroadcastsSessionSnapshot() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        CollaborationSessionSnapshot snapshot = snapshot(documentId);

        when(sessionService.leave(documentId, sessionId)).thenReturn(snapshot);

        collaborationController.leaveSession(documentId, new LeaveSessionRequest(sessionId));

        verify(sessionService).leave(documentId, sessionId);
        verify(broadcastService).broadcastSessionSnapshot(documentId, snapshot);
        verifyNoMoreInteractions(broadcastService);
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

        when(presenceService.publishPresence(documentId, request)).thenReturn(event);

        collaborationController.updatePresence(documentId, request);

        verify(presenceService).publishPresence(documentId, request);
        verify(broadcastService).broadcastPresenceEvent(documentId, event);
        verifyNoMoreInteractions(broadcastService);
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
