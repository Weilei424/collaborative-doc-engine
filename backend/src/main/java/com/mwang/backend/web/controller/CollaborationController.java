package com.mwang.backend.web.controller;

import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.service.CollaborationPresenceService;
import com.mwang.backend.service.CollaborationSessionService;
import com.mwang.backend.service.DocumentOperationService;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.service.exception.StaleClientException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.OperationErrorResponse;
import com.mwang.backend.web.model.LeaveSessionRequest;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import com.mwang.backend.web.model.SubmitOperationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@RequiredArgsConstructor
@Controller
public class CollaborationController {

    private final CollaborationSessionService collaborationSessionService;
    private final CollaborationPresenceService collaborationPresenceService;
    private final CollaborationBroadcastService collaborationBroadcastService;
    private final DocumentOperationService documentOperationService;
    private final RedisCollaborationEventPublisher redisCollaborationEventPublisher;

    @MessageMapping("/documents/{documentId}/sessions.join")
    public void joinSession(
            @DestinationVariable UUID documentId,
            SimpMessageHeaderAccessor headerAccessor) {
        collaborationBroadcastService.broadcastSessionSnapshot(
                documentId,
                collaborationSessionService.join(documentId, headerAccessor)
        );
    }

    @MessageMapping("/documents/{documentId}/sessions.leave")
    public void leaveSession(
            @DestinationVariable UUID documentId,
            @Payload(required = false) LeaveSessionRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        collaborationBroadcastService.broadcastSessionSnapshot(
                documentId,
                collaborationSessionService.leave(documentId, requireSessionId(request), headerAccessor)
        );
    }

    @MessageMapping("/documents/{documentId}/presence.update")
    public void updatePresence(
            @DestinationVariable UUID documentId,
            @Payload PresenceUpdateRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        collaborationBroadcastService.broadcastPresenceEvent(
                documentId,
                collaborationPresenceService.publishPresence(documentId, request, headerAccessor)
        );
    }

    @MessageMapping("/documents/{documentId}/operations.submit")
    public void submitOperation(
            @DestinationVariable UUID documentId,
            @Payload SubmitOperationRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        AcceptedOperationResponse response = documentOperationService.submitOperation(documentId, request, headerAccessor);
        collaborationBroadcastService.broadcastAcceptedOperation(documentId, response);
        redisCollaborationEventPublisher.publishAcceptedOperation(documentId, response);
    }

    private UUID requireSessionId(LeaveSessionRequest request) {
        if (request == null || request.sessionId() == null) {
            throw new InvalidCollaborationRequestException("Leave session requires sessionId");
        }
        return request.sessionId();
    }

    @MessageExceptionHandler(StaleClientException.class)
    @SendToUser(value = "/queue/errors.{documentId}", broadcast = false)
    public OperationErrorResponse handleStaleClient(
            StaleClientException ex,
            @DestinationVariable UUID documentId) {
        return new OperationErrorResponse("RESYNC_REQUIRED", ex.getOperationId(), ex.getCurrentServerVersion());
    }
}
