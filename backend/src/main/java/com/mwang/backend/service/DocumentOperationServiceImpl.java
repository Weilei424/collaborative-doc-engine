package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.DocumentTreeCache;
import com.mwang.backend.collaboration.OperationTransformer;
import com.mwang.backend.collaboration.ParsedAcceptedOp;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.model.DocumentTree;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CasMissException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.IdempotentOperationException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.service.exception.OperationConflictException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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
    private final MeterRegistry meterRegistry;
    private final DocumentTreeCache treeCache;
    private final DocumentOperationCommitter committer;
    private final int maxAttempts;

    private final Counter idempotentCounter;
    private final Counter noopCounter;
    private final Counter conflictedCounter;
    private final Counter operationsResyncRequiredCounter;
    private final Timer loadDocumentTimer;
    private final Timer loadInterveningOpsTimer;
    private final Timer otTransformLoopTimer;
    private final Timer perOpJsonParseTimer;
    private final Timer treeApplyTimer;
    private final Timer persistOperationTimer;

    public DocumentOperationServiceImpl(
            DocumentRepository documentRepository,
            DocumentOperationRepository operationRepository,
            CurrentUserProvider currentUserProvider,
            DocumentAuthorizationService authorizationService,
            OperationTransformer transformer,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            DocumentTreeCache treeCache,
            DocumentOperationCommitter committer,
            @Value("${collaboration.cas.max-attempts:5}") int maxAttempts) {
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.transformer = transformer;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.treeCache = treeCache;
        this.committer = committer;
        this.maxAttempts = maxAttempts;

        this.idempotentCounter = meterRegistry.counter("operations.idempotent");
        this.noopCounter = meterRegistry.counter("operations.noop");
        this.conflictedCounter = meterRegistry.counter("operations.conflicted");
        this.operationsResyncRequiredCounter = meterRegistry.counter("operations.resync_required");
        this.loadDocumentTimer = Timer.builder("loadDocument").register(meterRegistry);
        this.loadInterveningOpsTimer = Timer.builder("loadInterveningOps").register(meterRegistry);
        this.otTransformLoopTimer = Timer.builder("otTransformLoop").register(meterRegistry);
        this.perOpJsonParseTimer = Timer.builder("perOpJsonParse").register(meterRegistry);
        this.treeApplyTimer = Timer.builder("treeApply").register(meterRegistry);
        this.persistOperationTimer = Timer.builder("persistOperation").register(meterRegistry);
    }

    @Override
    public AcceptedOperationResponse submitOperation(
            UUID documentId, SubmitOperationRequest request, SimpMessageHeaderAccessor headerAccessor) {

        // Phase 1 — Validate (no DB)
        User actor = currentUserProvider.requireCurrentUser(headerAccessor);
        validatePayload(request.operationType(), request.payload());

        // Pre-loop idempotency fast path
        Optional<DocumentOperation> priorOpt =
                operationRepository.findByDocumentIdAndOperationId(documentId, request.operationId());
        if (priorOpt.isPresent()) {
            idempotentCounter.increment();
            return toResponse(priorOpt.get(), documentId);
        }

        String clientSessionId = headerAccessor != null && headerAccessor.getSessionId() != null
                ? headerAccessor.getSessionId() : "";

        // Phase 2 — Retry loop
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            // a. Read snapshot (no lock); eager-load owner + collaborators for ACL check
            Document document = loadDocumentTimer.record(() ->
                    documentRepository.findDetailedById(documentId)
                            .orElseThrow(() -> new DocumentNotFoundException(documentId)));

            List<DocumentOperation> intervening = loadInterveningOpsTimer.record(() ->
                    operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                            documentId, request.baseVersion()));

            // b. Idempotency re-check against fresh snapshot
            boolean alreadyAccepted = intervening.stream()
                    .anyMatch(op -> op.getOperationId().equals(request.operationId()));
            if (alreadyAccepted) {
                idempotentCounter.increment();
                DocumentOperation acceptedOp = intervening.stream()
                        .filter(op -> op.getOperationId().equals(request.operationId()))
                        .findFirst().orElseThrow();
                return toResponse(acceptedOp, documentId);
            }

            // c. ACL check (not retried — revocation is final)
            authorizationService.assertCanWrite(document, actor);

            if (!intervening.isEmpty()) {
                conflictedCounter.increment();
            }

            // d. Speculative transform (outside transaction)
            long parseStart = System.nanoTime();
            List<ParsedAcceptedOp> parsed;
            try {
                parsed = intervening.stream()
                        .map(op -> {
                            try {
                                return new ParsedAcceptedOp(op.getOperationType(),
                                        objectMapper.readTree(op.getPayload()));
                            } catch (Exception e) {
                                throw new IllegalStateException(
                                        "Failed to deserialize accepted operation payload", e);
                            }
                        })
                        .toList();
            } finally {
                perOpJsonParseTimer.record(System.nanoTime() - parseStart,
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            }

            DocumentOperationType currentType = request.operationType();
            JsonNode currentPayload = request.payload();
            long loopStart = System.nanoTime();
            try {
                for (ParsedAcceptedOp accepted : parsed) {
                    Optional<JsonNode> transformed = transformer.transform(
                            currentType, currentPayload, accepted.type(), accepted.payload());
                    if (transformed.isEmpty()) {
                        noopCounter.increment();
                        currentType = DocumentOperationType.NO_OP;
                        currentPayload = objectMapper.createObjectNode();
                        break;
                    }
                    currentPayload = transformed.get();
                }
            } finally {
                otTransformLoopTimer.record(System.nanoTime() - loopStart,
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            }

            long expectedVersion = document.getCurrentVersion();
            long nextVersion = expectedVersion + 1;
            final DocumentOperationType finalType = currentType;
            final JsonNode finalPayload = currentPayload;

            long treeStart = System.nanoTime();
            String serializedContent;
            DocumentTree tree;
            try {
                final Document doc = document;
                tree = treeCache.get(documentId, expectedVersion)
                        .orElseGet(() -> {
                            try {
                                return objectMapper.readValue(doc.getContent(), DocumentTree.class);
                            } catch (Exception e) {
                                throw new IllegalStateException(
                                        "Failed to deserialize document tree from content", e);
                            }
                        });
                JsonNode enrichedPayload = finalPayload;
                if (finalType != DocumentOperationType.NO_OP) {
                    enrichedPayload = tree.applyOperation(finalType, finalPayload);
                    currentPayload = enrichedPayload;
                }
                serializedContent = objectMapper.writeValueAsString(tree);
            } catch (Exception e) {
                throw new InvalidOperationException(
                        "Failed to apply operation to document: " + e.getMessage(), e);
            } finally {
                treeApplyTimer.record(System.nanoTime() - treeStart,
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            }

            // e. CAS commit
            final DocumentOperationType committedType = currentType;
            final JsonNode committedPayload = currentPayload;
            final long committedNextVersion = nextVersion;
            final String committedContent = serializedContent;
            final Document committedDocument = document;
            final DocumentTree committedTree = tree;

            try {
                DocumentOperation accepted = persistOperationTimer.record(() ->
                        committer.commit(documentId, expectedVersion, committedNextVersion,
                                committedContent, committedDocument, actor,
                                request.operationId(), clientSessionId,
                                request.baseVersion(), committedType, committedPayload));

                // Update cache only after the CAS commit succeeds
                treeCache.put(documentId, committedNextVersion, committedTree);
                treeCache.evict(documentId, expectedVersion);

                meterRegistry.counter("operations.accepted", "type", committedType.name()).increment();
                return toResponse(accepted, documentId);

            } catch (CasMissException e) {
                meterRegistry.counter("operations.retries", "attempt", String.valueOf(attempt)).increment();
                // continue to next attempt

            } catch (IdempotentOperationException e) {
                idempotentCounter.increment();
                DocumentOperation acceptedOp = operationRepository
                        .findByDocumentIdAndOperationId(documentId, e.getOperationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotent op not found after constraint violation: " + e.getOperationId()));
                return toResponse(acceptedOp, documentId);
            }
        }

        throw new OperationConflictException(
                "Operation could not be committed after " + maxAttempts + " attempts for document " + documentId);
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
