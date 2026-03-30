package com.mwang.backend.collaboration;

import com.mwang.backend.service.CollaborationBroadcastService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AccessRevokedEventListener {

    private final CollaborationBroadcastService collaborationBroadcastService;
    private final RedisCollaborationEventPublisher redisCollaborationEventPublisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccessRevoked(AccessRevokedEvent event) {
        collaborationBroadcastService.broadcastAccessRevoked(event.documentId(), event.revokedUserId());
        redisCollaborationEventPublisher.publishAccessRevoked(event.documentId(), event.revokedUserId());
    }
}
