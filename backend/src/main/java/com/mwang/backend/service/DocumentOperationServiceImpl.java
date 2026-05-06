package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentOperationServiceImpl implements DocumentOperationService {

    private final DocumentRepository documentRepository;
    private final DocumentOperationRepository operationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final DocumentAuthorizationService authorizationService;
    private final DocumentOperationBatcher batcher;
    private final CollaborationBroadcastService broadcastService;
    private final RedisCollaborationEventPublisher redisPublisher;
    private final ObjectMapper objectMapper;
    private final Counter idempotentCounter;

    public DocumentOperationServiceImpl(
            DocumentRepository documentRepository,
            DocumentOperationRepository operationRepository,
            CurrentUserProvider currentUserProvider,
            DocumentAuthorizationService authorizationService,
            DocumentOperationBatcher batcher,
            CollaborationBroadcastService broadcastService,
            RedisCollaborationEventPublisher redisPublisher,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.batcher = batcher;
        this.broadcastService = broadcastService;
        this.redisPublisher = redisPublisher;
        this.objectMapper = objectMapper;
        this.idempotentCounter = meterRegistry.counter("operations.idempotent");
    }

    @Override
    public void submitOperation(
            UUID documentId, SubmitOperationRequest request, SimpMessageHeaderAccessor headerAccessor) {

        User actor = currentUserProvider.requireCurrentUser(headerAccessor);
        validatePayload(request.operationType(), request.payload());

        Optional<DocumentOperation> priorOpt =
                operationRepository.findByDocumentIdAndOperationId(documentId, request.operationId());
        if (priorOpt.isPresent()) {
            Document aclDoc = documentRepository.findDetailedById(documentId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            authorizationService.assertCanWrite(aclDoc, actor);
            idempotentCounter.increment();
            AcceptedOperationResponse response = toResponse(priorOpt.get(), documentId);
            broadcastService.broadcastAcceptedOperation(documentId, response);
            redisPublisher.publishAcceptedOperation(documentId, response);
            return;
        }

        String clientSessionId = headerAccessor != null && headerAccessor.getSessionId() != null
                ? headerAccessor.getSessionId() : "";
        String principalName = headerAccessor != null && headerAccessor.getUser() != null
                ? headerAccessor.getUser().getName() : "";

        batcher.enqueue(new PendingOperation(documentId, request, actor, clientSessionId, principalName));
    }

    private void validatePayload(DocumentOperationType type, JsonNode payload) {
        if (type == null) {
            throw new InvalidOperationException("Operation type is required");
        }
        if (payload == null || payload.isNull()) {
            throw new InvalidOperationException("Payload is required");
        }
        if (type == DocumentOperationType.NO_OP) {
            throw new InvalidOperationException("NO_OP cannot be submitted by clients");
        }
        if (!payload.has("path") || !payload.get("path").isArray() || payload.get("path").isEmpty()) {
            throw new InvalidOperationException("Payload must include a non-empty 'path' array");
        }
        switch (type) {
            case INSERT_TEXT -> {
                if (!payload.has("offset") || !payload.has("text"))
                    throw new InvalidOperationException("INSERT_TEXT requires 'offset' and 'text'");
                if (payload.get("offset").asInt() < 0)
                    throw new InvalidOperationException("INSERT_TEXT offset must be non-negative");
            }
            case DELETE_RANGE -> {
                if (!payload.has("offset") || !payload.has("length"))
                    throw new InvalidOperationException("DELETE_RANGE requires 'offset' and 'length'");
                if (payload.get("offset").asInt() < 0 || payload.get("length").asInt() <= 0)
                    throw new InvalidOperationException(
                            "DELETE_RANGE offset must be non-negative and length positive");
            }
            case FORMAT_RANGE -> {
                if (!payload.has("offset") || !payload.has("length") || !payload.has("attributes"))
                    throw new InvalidOperationException(
                            "FORMAT_RANGE requires 'offset', 'length', and 'attributes'");
            }
            case SPLIT_BLOCK -> {
                if (!payload.has("offset"))
                    throw new InvalidOperationException("SPLIT_BLOCK requires 'offset'");
            }
            case MERGE_BLOCK -> { /* path-only */ }
            case SET_BLOCK_TYPE -> {
                if (!payload.has("blockType"))
                    throw new InvalidOperationException("SET_BLOCK_TYPE requires 'blockType'");
            }
        }
    }

    private AcceptedOperationResponse toResponse(DocumentOperation op, UUID documentId) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(op.getPayload());
        } catch (Exception e) {
            payload = objectMapper.createObjectNode();
        }
        return new AcceptedOperationResponse(
                op.getOperationId(), documentId, op.getServerVersion(),
                op.getOperationType(), payload, op.getActor().getId(),
                op.getClientSessionId(), op.getCreatedAt() != null ? op.getCreatedAt() : Instant.now());
    }
}
