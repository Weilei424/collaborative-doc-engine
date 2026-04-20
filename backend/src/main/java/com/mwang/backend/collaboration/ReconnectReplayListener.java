package com.mwang.backend.collaboration;

import com.mwang.backend.domain.User;
import com.mwang.backend.service.OperationHistoryService;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.OperationPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReconnectReplayListener implements ApplicationListener<SessionConnectEvent> {

    private static final String HEADER_PREFIX = "X-Last-Server-Version-";
    private static final int PAGE_SIZE = 500;
    private static final int MAX_TOTAL = 2000;

    private final OperationHistoryService operationHistoryService;
    private final SimpMessagingTemplate messagingTemplate;

    @Async
    @Override
    public void onApplicationEvent(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) return;
        Object userObj = sessionAttributes.get("user");
        if (!(userObj instanceof User actor)) return;

        Map<String, List<String>> nativeHeaders = accessor.toNativeHeaderMap();
        if (nativeHeaders == null) return;

        nativeHeaders.forEach((headerName, values) -> {
            if (!headerName.startsWith(HEADER_PREFIX) || values == null || values.isEmpty()) return;

            String docIdStr = headerName.substring(HEADER_PREFIX.length());
            UUID documentId;
            long sinceVersion;
            try {
                documentId = UUID.fromString(docIdStr);
                sinceVersion = Long.parseLong(values.get(0));
            } catch (Exception e) {
                return;
            }

            try {
                replayOps(actor, documentId, sinceVersion);
            } catch (Exception e) {
                // session may have already disconnected; ignore
            }
        });
    }

    private void replayOps(User actor, UUID documentId, long sinceVersion) {
        long totalSent = 0;
        long currentSince = sinceVersion;
        boolean hasMore = true;

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
                        op);
                currentSince = op.serverVersion();
            }

            totalSent += page.operations().size();
            hasMore = page.hasMore();
        }
    }
}
