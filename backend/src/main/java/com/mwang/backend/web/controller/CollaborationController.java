package com.mwang.backend.web.controller;

import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.service.CollaborationPresenceService;
import com.mwang.backend.service.CollaborationSessionService;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.web.model.JoinSessionRequest;
import com.mwang.backend.web.model.LeaveSessionRequest;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@Controller
public class CollaborationController {

    private final CollaborationSessionService collaborationSessionService;
    private final CollaborationPresenceService collaborationPresenceService;
    private final CollaborationBroadcastService collaborationBroadcastService;

    @MessageMapping("/documents/{documentId}/sessions.join")
    public void joinSession(
            @DestinationVariable UUID documentId,
            @Payload(required = false) JoinSessionRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        collaborationBroadcastService.broadcastSessionSnapshot(
                documentId,
                collaborationSessionService.join(documentId, request == null ? null : request.clientSessionHint(), sessionAttributes(headerAccessor))
        );
    }

    @MessageMapping("/documents/{documentId}/sessions.leave")
    public void leaveSession(
            @DestinationVariable UUID documentId,
            @Payload(required = false) LeaveSessionRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        collaborationBroadcastService.broadcastSessionSnapshot(
                documentId,
                collaborationSessionService.leave(documentId, requireSessionId(request), sessionAttributes(headerAccessor))
        );
    }

    @MessageMapping("/documents/{documentId}/presence.update")
    public void updatePresence(
            @DestinationVariable UUID documentId,
            @Payload PresenceUpdateRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        collaborationBroadcastService.broadcastPresenceEvent(
                documentId,
                collaborationPresenceService.publishPresence(documentId, request, sessionAttributes(headerAccessor))
        );
    }

    private UUID requireSessionId(LeaveSessionRequest request) {
        if (request == null || request.sessionId() == null) {
            throw new InvalidCollaborationRequestException("Leave session requires sessionId");
        }
        return request.sessionId();
    }

    private Map<String, Object> sessionAttributes(SimpMessageHeaderAccessor headerAccessor) {
        return headerAccessor == null ? null : headerAccessor.getSessionAttributes();
    }
}
