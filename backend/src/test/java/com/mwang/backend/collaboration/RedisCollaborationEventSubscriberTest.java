package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                null,
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
                event,
                null
        ));

        verify(broadcastService, never()).broadcastPresenceEvent(documentId, event);
        verify(broadcastService, never()).broadcastSessionSnapshot(documentId, null);
    }

    @Test
    void forwardsAcceptedOperationsFromOtherInstancesToLocalBroadcasts() {
        CollaborationBroadcastService broadcastService = mock(CollaborationBroadcastService.class);
        RedisCollaborationEventSubscriber subscriber = new RedisCollaborationEventSubscriber(
                new ObjectMapper().findAndRegisterModules(), broadcastService, "local-instance");
        UUID documentId = UUID.randomUUID();
        AcceptedOperationResponse response = buildResponse(documentId);

        subscriber.onMessage(new RedisAcceptedOperationEvent("remote-instance", response));

        verify(broadcastService).broadcastAcceptedOperation(documentId, response);
    }

    @Test
    void ignoresAcceptedOperationsPublishedByTheSameInstance() {
        CollaborationBroadcastService broadcastService = mock(CollaborationBroadcastService.class);
        RedisCollaborationEventSubscriber subscriber = new RedisCollaborationEventSubscriber(
                new ObjectMapper().findAndRegisterModules(), broadcastService, "local-instance");
        UUID documentId = UUID.randomUUID();

        subscriber.onMessage(new RedisAcceptedOperationEvent("local-instance", buildResponse(documentId)));

        verify(broadcastService, never()).broadcastAcceptedOperation(any(), any());
    }

    @Test
    void routesOperationChannelMessageToAcceptedOperationHandler() throws Exception {
        CollaborationBroadcastService broadcastService = mock(CollaborationBroadcastService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RedisCollaborationEventSubscriber subscriber = new RedisCollaborationEventSubscriber(
                objectMapper, broadcastService, "local-instance");
        UUID documentId = UUID.randomUUID();
        AcceptedOperationResponse response = buildResponse(documentId);
        RedisAcceptedOperationEvent event = new RedisAcceptedOperationEvent("remote-instance", response);

        Message message = mock(Message.class);
        when(message.getChannel()).thenReturn(
                RedisCollaborationChannels.documentOperations(documentId).getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));

        subscriber.onMessage(message, null);

        verify(broadcastService).broadcastAcceptedOperation(documentId, response);
    }

    @Test
    void routesEventsChannelMessageToCollaborationEventHandler() throws Exception {
        CollaborationBroadcastService broadcastService = mock(CollaborationBroadcastService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RedisCollaborationEventSubscriber subscriber = new RedisCollaborationEventSubscriber(
                objectMapper, broadcastService, "local-instance");
        UUID documentId = UUID.randomUUID();
        CollaborationSessionSnapshot snapshot = new CollaborationSessionSnapshot(documentId, List.of());
        RedisCollaborationEvent event = new RedisCollaborationEvent(
                "remote-instance", RedisCollaborationEventType.SESSION_SNAPSHOT, documentId, snapshot, null, null);

        Message message = mock(Message.class);
        when(message.getChannel()).thenReturn(RedisCollaborationChannels.EVENTS.getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));

        subscriber.onMessage(message, null);

        verify(broadcastService).broadcastSessionSnapshot(documentId, snapshot);
    }

    private AcceptedOperationResponse buildResponse(UUID documentId) {
        return new AcceptedOperationResponse(
                UUID.randomUUID(), documentId, 1L,
                DocumentOperationType.INSERT_TEXT, NullNode.instance,
                UUID.randomUUID(), "session-1", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        );
    }
}
