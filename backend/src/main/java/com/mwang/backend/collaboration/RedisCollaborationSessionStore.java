package com.mwang.backend.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisCollaborationSessionStore implements CollaborationSessionStore {

    private final HashOperations<String, String, String> hashOperations;
    private final ObjectMapper objectMapper;

    public RedisCollaborationSessionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.hashOperations = redisTemplate.opsForHash();
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(CollaborationSessionResponse session) {
        try {
            hashOperations.put(
                    RedisCollaborationChannels.documentSessions(session.documentId()),
                    session.sessionId().toString(),
                    objectMapper.writeValueAsString(session)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize collaboration session", exception);
        }
    }

    @Override
    public void remove(UUID documentId, UUID sessionId) {
        hashOperations.delete(RedisCollaborationChannels.documentSessions(documentId), sessionId.toString());
    }

    @Override
    public Optional<CollaborationSessionResponse> findBySessionId(UUID documentId, UUID sessionId) {
        String serialized = hashOperations.get(RedisCollaborationChannels.documentSessions(documentId), sessionId.toString());
        if (serialized == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(serialized));
    }

    @Override
    public List<CollaborationSessionResponse> findByDocumentId(UUID documentId) {
        Map<String, String> entries = hashOperations.entries(RedisCollaborationChannels.documentSessions(documentId));
        return entries.values().stream()
                .map(this::deserialize)
                .sorted(Comparator.comparing(CollaborationSessionResponse::joinedAt))
                .toList();
    }

    private CollaborationSessionResponse deserialize(String serialized) {
        try {
            return objectMapper.readValue(serialized, CollaborationSessionResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize collaboration session", exception);
        }
    }
}
