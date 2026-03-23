package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisCollaborationEventSubscriberTest {

    @Test
    void forwardsSessionSnapshotsFromOtherInstancesToLocalBroadcasts() {
        CollaborationBroadcastService broadcastService = mock(CollaborationBroadcastService.class);
        RedisCollaborationEventSubscriber subscriber = new RedisCollaborationEventSubscriber(new ObjectMapper().findAndRegisterModules(), broadcastService, "local-instance");
        UUID documentId = UUID.randomUUID();
        CollaborationSessionSnapshot snapshot = new CollaborationSessionSnapshot(documentId, List.of());
        RedisCollaborationEvent event = new RedisCollaborationEvent(
                "remote-instance",
                RedisCollaborationEventType.SESSION_SNAPSHOT,
                documentId,
                snapshot,
                null
        );

        subscriber.onMessage(event);

        verify(broadcastService).broadcastSessionSnapshot(documentId, snapshot);
    }

    @Test
    void ignoresEventsPublishedByTheSameInstance() {
        CollaborationBroadcastService broadcastService = mock(CollaborationBroadcastService.class);
        RedisCollaborationEventSubscriber subscriber = new RedisCollaborationEventSubscriber(new ObjectMapper().findAndRegisterModules(), broadcastService, "local-instance");
        UUID documentId = UUID.randomUUID();
        PresenceEventResponse event = new PresenceEventResponse(
                documentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "tester",
                PresenceType.TYPING,
                Map.of("typing", true),
                Instant.parse("2026-03-23T12:01:00Z")
        );

        subscriber.onMessage(new RedisCollaborationEvent(
                "local-instance",
                RedisCollaborationEventType.PRESENCE_UPDATED,
                documentId,
                null,
                event
        ));

        verify(broadcastService, never()).broadcastPresenceEvent(documentId, event);
        verify(broadcastService, never()).broadcastSessionSnapshot(documentId, null);
    }
}
