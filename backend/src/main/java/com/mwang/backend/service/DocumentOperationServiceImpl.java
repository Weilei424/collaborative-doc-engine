package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.OperationTransformer;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.model.DocumentTree;
import com.mwang.backend.kafka.AcceptedOperationDomainEvent;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentOperationServiceImpl implements DocumentOperationService {

    private final DocumentRepository documentRepository;
    private final DocumentOperationRepository operationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final DocumentAuthorizationService authorizationService;
    private final OperationTransformer transformer;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;
    private final Counter conflictedCounter;
    private final Counter noopCounter;
    private final Counter idempotentCounter;
    private final MeterRegistry meterRegistry;

    public DocumentOperationServiceImpl(
            DocumentRepository documentRepository,
            DocumentOperationRepository operationRepository,
            CurrentUserProvider currentUserProvider,
            DocumentAuthorizationService authorizationService,
            OperationTransformer transformer,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            EntityManager entityManager,
            MeterRegistry meterRegistry) {
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.transformer = transformer;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
        this.meterRegistry = meterRegistry;
        this.conflictedCounter = meterRegistry.counter("operations.conflicted");
        this.noopCounter = meterRegistry.counter("operations.noop");
        this.idempotentCounter = meterRegistry.counter("operations.idempotent");
    }

    @Override
    @Transactional
    public AcceptedOperationResponse submitOperation(
            UUID documentId, SubmitOperationRequest request, Map<String, Object> sessionAttributes) {

        // 1. Actor resolution
        User actor = currentUserProvider.requireCurrentUser(sessionAttributes);

        // 2. Payload validation (fail fast before touching the DB)
        validatePayload(request.operationType(), request.payload());

        // 3. Load document for auth (non-locking read — idempotency fast-path must still be authorized)
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        authorizationService.assertCanWrite(document, actor);

        // 4. Idempotency check (post-auth, pre-lock — avoids acquiring write lock on duplicate submissions)
        Optional<DocumentOperation> existingOpt = operationRepository
                .findByDocumentIdAndOperationId(documentId, request.operationId());
        if (existingOpt.isPresent()) {
            idempotentCounter.increment();
            return toResponse(existingOpt.get(), documentId);
        }

        // 5. Acquire pessimistic lock for new operation.
        // Evict the document from the L1 session cache before locking so that Hibernate
        // re-reads a fresh copy from the DB (including the current @Version value).
        // Without this, a concurrent commit between the non-locking findById above and
        // this lock acquisition will cause Hibernate to detect a stale @Version on the
        // cached entity and throw ObjectOptimisticLockingFailureException.
        entityManager.detach(document);
        document = documentRepository.findByIdWithPessimisticLock(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        // 5a. Re-check authorization against the locked document (closes TOCTOU gap between the
        //     non-locking read above and the now-acquired pessimistic write lock)
        authorizationService.assertCanWrite(document, actor);

        // 5b. Re-check idempotency under the lock — a concurrent duplicate submission may have
        //     passed the pre-lock check above, blocked here, and committed while we waited.
        //     Without this re-check, the second thread would attempt a duplicate insert and hit
        //     the unique constraint instead of returning the original accepted response.
        existingOpt = operationRepository.findByDocumentIdAndOperationId(documentId, request.operationId());
        if (existingOpt.isPresent()) {
            idempotentCounter.increment();
            return toResponse(existingOpt.get(), documentId);
        }

        // 6. Load intervening operations (document is already locked by pessimistic write)
        List<DocumentOperation> intervening = operationRepository
                .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(documentId, request.baseVersion());
        if (!intervening.isEmpty()) {
            conflictedCounter.increment();
        }

        // 7. Transform incoming op against each intervening op in order (short-circuit on no-op)
        DocumentOperationType currentType = request.operationType();
        JsonNode currentPayload = request.payload();
        for (DocumentOperation accepted : intervening) {
            JsonNode acceptedPayload;
            try {
                acceptedPayload = objectMapper.readTree(accepted.getPayload());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize accepted operation payload", e);
            }
            Optional<JsonNode> transformed = transformer.transform(
                    currentType, currentPayload, accepted.getOperationType(), acceptedPayload);
            if (transformed.isEmpty()) {
                noopCounter.increment();
                currentType = DocumentOperationType.NO_OP;
                currentPayload = objectMapper.createObjectNode();
                break;
            }
            currentPayload = transformed.get();
        }

        // 8. Apply to document tree (skip for NO_OP)
        long nextVersion = document.getCurrentVersion() + 1;
        if (currentType != DocumentOperationType.NO_OP) {
            try {
                DocumentTree tree = objectMapper.readValue(document.getContent(), DocumentTree.class);
                JsonNode enrichedPayload = tree.applyOperation(currentType, currentPayload);
                currentPayload = enrichedPayload;
                document.setContent(objectMapper.writeValueAsString(tree));
            } catch (Exception e) {
                throw new InvalidOperationException("Failed to apply operation to document: " + e.getMessage());
            }
        }

        // 9. Persist accepted operation and updated document
        Object rawSessionId = sessionAttributes.get("simpSessionId");
        String clientSessionId = rawSessionId != null ? rawSessionId.toString() : "";
        DocumentOperation accepted = DocumentOperation.builder()
                .document(document)
                .actor(actor)
                .operationId(request.operationId())
                .clientSessionId(clientSessionId)
                .baseVersion(request.baseVersion())
                .serverVersion(nextVersion)
                .operationType(currentType)
                .payload(payloadString(currentPayload))
                .build();

        operationRepository.save(accepted);
        document.setCurrentVersion(nextVersion);
        documentRepository.save(document);
        meterRegistry.counter("operations.accepted", "type", currentType.name()).increment();

        AcceptedOperationResponse acceptedResponse = new AcceptedOperationResponse(
                request.operationId(), documentId, nextVersion,
                currentType, currentPayload, actor.getId(),
                clientSessionId, accepted.getCreatedAt() != null ? accepted.getCreatedAt() : Instant.now());

        eventPublisher.publishEvent(new AcceptedOperationDomainEvent(acceptedResponse, request.baseVersion()));

        return acceptedResponse;
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
                    throw new InvalidOperationException("DELETE_RANGE offset must be non-negative and length positive");
            }
            case FORMAT_RANGE -> {
                if (!payload.has("offset") || !payload.has("length") || !payload.has("attributes"))
                    throw new InvalidOperationException("FORMAT_RANGE requires 'offset', 'length', and 'attributes'");
            }
            case SPLIT_BLOCK -> {
                if (!payload.has("offset"))
                    throw new InvalidOperationException("SPLIT_BLOCK requires 'offset'");
            }
            case MERGE_BLOCK -> { /* path-only, already validated */ }
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
                op.getClientSessionId(), op.getCreatedAt());
    }

    private String payloadString(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize payload", e);
        }
    }
}
