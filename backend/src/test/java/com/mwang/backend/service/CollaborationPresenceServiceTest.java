package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.PresenceType;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollaborationPresenceServiceTest {

    @Mock
    private CollaborationSessionStore collaborationSessionStore;

    @Mock
    private RedisCollaborationEventPublisher eventPublisher;

    @InjectMocks
    private CollaborationPresenceServiceImpl presenceService;

    @Test
    void publishPresenceRequiresAnActiveSession() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.TYPING, Map.of("typing", true));

        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> presenceService.publishPresence(documentId, request))
                .isInstanceOf(CollaborationSessionNotFoundException.class);
    }

    @Test
    void publishPresenceBuildsCanonicalEventForActiveSession() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.CURSOR_POSITION, Map.of("line", 4));
        CollaborationSessionResponse session = new CollaborationSessionResponse(
                sessionId,
                documentId,
                userId,
                "tester",
                Instant.parse("2026-03-23T12:00:00Z"),
                Instant.parse("2026-03-23T12:01:00Z")
        );

        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.of(session));

        var event = presenceService.publishPresence(documentId, request);

        assertThat(event.documentId()).isEqualTo(documentId);
        assertThat(event.sessionId()).isEqualTo(sessionId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.username()).isEqualTo("tester");
        assertThat(event.type()).isEqualTo(PresenceType.CURSOR_POSITION);
        assertThat(event.payload()).containsEntry("line", 4);
        verify(eventPublisher).publishPresenceEvent(event);
    }
}
