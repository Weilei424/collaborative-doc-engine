package com.mwang.backend.collaboration;

import com.mwang.backend.domain.User;
import com.mwang.backend.service.OperationHistoryService;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.OperationPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReconnectReplayListener implements ApplicationListener<SessionSubscribeEvent> {

    private static final String CATCHUP_DEST_PREFIX = "/user/queue/catchup.";
    private static final String VERSION_HEADER = "X-Last-Server-Version";
    private static final int PAGE_SIZE = 500;
    private static final int MAX_TOTAL = 2000;

    private final OperationHistoryService operationHistoryService;
    private final SimpMessagingTemplate messagingTemplate;

    @Async
    @Override
    public void onApplicationEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(CATCHUP_DEST_PREFIX)) return;

        String docIdStr = destination.substring(CATCHUP_DEST_PREFIX.length());
        UUID documentId;
        try {
            documentId = UUID.fromString(docIdStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        String versionStr = accessor.getFirstNativeHeader(VERSION_HEADER);
        if (versionStr == null) return;
        long sinceVersion;
        try {
            sinceVersion = Long.parseLong(versionStr);
        } catch (NumberFormatException e) {
            return;
        }

        String sessionId = accessor.getSessionId();

        java.util.Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) return;
        Object userObj = sessionAttributes.get("user");
        if (!(userObj instanceof User actor)) return;

        try {
            replayOps(actor, documentId, sinceVersion, sessionId);
        } catch (Exception e) {
            // session may have already disconnected; ignore
        }
    }

    private void replayOps(User actor, UUID documentId, long sinceVersion, String sessionId) {
        long totalSent = 0;
        long currentSince = sinceVersion;
        boolean hasMore = true;

        MessageHeaders sessionHeaders = buildSessionHeaders(sessionId);

        while (hasMore && totalSent < MAX_TOTAL) {
            int pageLimit = (int) Math.min(PAGE_SIZE, MAX_TOTAL - totalSent);
            OperationPageResponse page;
            try {
                page = operationHistoryService.getOperationPage(documentId, currentSince, pageLimit, actor);
            } catch (Exception e) {
                return; // ACL denied, rate limited, or doc not found — stop replay silently
            }

            if (page.operations().isEmpty()) {
                break; // guard: misbehaving service returns hasMore=true with no ops
            }

            for (AcceptedOperationResponse op : page.operations()) {
                messagingTemplate.convertAndSendToUser(
                        actor.getId().toString(),
                        "/queue/catchup." + documentId,
                        op,
                        sessionHeaders);
                currentSince = op.serverVersion();
            }

            totalSent += page.operations().size();
            hasMore = page.hasMore();
        }
    }

    private static MessageHeaders buildSessionHeaders(String sessionId) {
        SimpMessageHeaderAccessor sha = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        sha.setSessionId(sessionId);
        sha.setLeaveMutable(true);
        return sha.getMessageHeaders();
    }
}
