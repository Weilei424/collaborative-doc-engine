package com.mwang.backend.web.controller;

import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.service.CollaborationPresenceService;
import com.mwang.backend.service.CollaborationSessionService;
import com.mwang.backend.web.model.JoinSessionRequest;
import com.mwang.backend.web.model.LeaveSessionRequest;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@RequiredArgsConstructor
@Controller
public class CollaborationController {

    private final CollaborationSessionService collaborationSessionService;
    private final CollaborationPresenceService collaborationPresenceService;
    private final CollaborationBroadcastService collaborationBroadcastService;

    @MessageMapping("/documents/{documentId}/sessions.join")
    public void joinSession(@DestinationVariable UUID documentId, @Payload JoinSessionRequest request) {
        collaborationBroadcastService.broadcastSessionSnapshot(
                documentId,
                collaborationSessionService.join(documentId, request.clientSessionHint())
        );
    }

    @MessageMapping("/documents/{documentId}/sessions.leave")
    public void leaveSession(@DestinationVariable UUID documentId, @Payload LeaveSessionRequest request) {
        collaborationBroadcastService.broadcastSessionSnapshot(
                documentId,
                collaborationSessionService.leave(documentId, request.sessionId())
        );
    }

    @MessageMapping("/documents/{documentId}/presence.update")
    public void updatePresence(@DestinationVariable UUID documentId, @Payload PresenceUpdateRequest request) {
        collaborationBroadcastService.broadcastPresenceEvent(
                documentId,
                collaborationPresenceService.publishPresence(documentId, request)
        );
    }
}
