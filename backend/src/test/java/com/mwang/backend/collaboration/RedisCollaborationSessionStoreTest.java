package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisCollaborationSessionStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisCollaborationSessionStore store;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        store = new RedisCollaborationSessionStore(redisTemplate, objectMapper);
    }

    @Test
    void saveStoresSessionInDocumentHash() throws Exception {
        CollaborationSessionResponse session = session();

        store.save(session);

        verify(hashOperations).put(
                RedisCollaborationChannels.documentSessions(session.documentId()),
                session.sessionId().toString(),
                objectMapper.writeValueAsString(session)
        );
    }

    @Test
    void findByDocumentIdReturnsAllStoredSessions() throws Exception {
        CollaborationSessionResponse session = session();
        String key = RedisCollaborationChannels.documentSessions(session.documentId());
        String encoded = objectMapper.writeValueAsString(session);

        when(hashOperations.entries(key)).thenReturn(Map.of(session.sessionId().toString(), encoded));

        List<CollaborationSessionResponse> sessions = store.findByDocumentId(session.documentId());

        assertThat(sessions).containsExactly(session);
    }

    @Test
    void findBySessionIdReturnsDecodedSessionWhenPresent() throws Exception {
        CollaborationSessionResponse session = session();
        String key = RedisCollaborationChannels.documentSessions(session.documentId());
        String encoded = objectMapper.writeValueAsString(session);

        when(hashOperations.get(key, session.sessionId().toString())).thenReturn(encoded);

        Optional<CollaborationSessionResponse> found = store.findBySessionId(session.documentId(), session.sessionId());

        assertThat(found).contains(session);
    }

    @Test
    void removeDeletesSessionFromDocumentHash() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        store.remove(documentId, sessionId);

        verify(hashOperations).delete(RedisCollaborationChannels.documentSessions(documentId), sessionId.toString());
    }

    private CollaborationSessionResponse session() {
        UUID documentId = UUID.randomUUID();
        return new CollaborationSessionResponse(
                UUID.randomUUID(),
                documentId,
                UUID.randomUUID(),
                "tester",
                Instant.parse("2026-03-23T12:00:00Z"),
                Instant.parse("2026-03-23T12:01:00Z")
        );
    }
}
