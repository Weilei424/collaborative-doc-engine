package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.DocumentTreeCache;
import com.mwang.backend.collaboration.ParsedAcceptedOp;
import com.mwang.backend.collaboration.OperationTransformer;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.model.DocumentTree;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final EntityManager entityManager;
    private final Counter conflictedCounter;
    private final Counter noopCounter;
    private final Counter idempotentCounter;
    private final Counter operationsRetriesCounter;    // placeholder — incremented in P19
    private final Counter operationsResyncRequiredCounter; // placeholder — incremented in P20
    private final MeterRegistry meterRegistry;
    private final Timer loadDocumentTimer;
    private final Timer lockAcquisitionTimer;
    private final Timer loadInterveningOpsTimer;
    private final Timer otTransformLoopTimer;
    private final Timer perOpJsonParseTimer;
    private final Timer treeApplyTimer;
    private final Timer persistOperationTimer;
    private final DocumentTreeCache treeCache;

    public DocumentOperationServiceImpl(
            DocumentRepository documentRepository,
            DocumentOperationRepository operationRepository,
            CurrentUserProvider currentUserProvider,
            DocumentAuthorizationService authorizationService,
            OperationTransformer transformer,
            ObjectMapper objectMapper,
            EntityManager entityManager,
            MeterRegistry meterRegistry,
            DocumentTreeCache treeCache) {
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.transformer = transformer;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.meterRegistry = meterRegistry;
        this.treeCache = treeCache;
        this.conflictedCounter = meterRegistry.counter("operations.conflicted");
        this.noopCounter = meterRegistry.counter("operations.noop");
        this.idempotentCounter = meterRegistry.counter("operations.idempotent");
        this.operationsRetriesCounter = meterRegistry.counter("operations.retries");
        this.operationsResyncRequiredCounter = meterRegistry.counter("operations.resync_required");
        this.loadDocumentTimer = Timer.builder("loadDocument").register(meterRegistry);
        this.lockAcquisitionTimer = Timer.builder("lockAcquisition").register(meterRegistry);
        this.loadInterveningOpsTimer = Timer.builder("loadInterveningOps").register(meterRegistry);
        this.otTransformLoopTimer = Timer.builder("otTransformLoop").register(meterRegistry);
        this.perOpJsonParseTimer = Timer.builder("perOpJsonParse").register(meterRegistry);
        this.treeApplyTimer = Timer.builder("treeApply").register(meterRegistry);
        this.persistOperationTimer = Timer.builder("persistOperation").register(meterRegistry);
    }

    @Override
    @Transactional
    public AcceptedOperationResponse submitOperation(
            UUID documentId, SubmitOperationRequest request, SimpMessageHeaderAccessor headerAccessor) {

        // 1. Actor resolution
        User actor = currentUserProvider.requireCurrentUser(headerAccessor);

        // 2. Payload validation (fail fast before touching the DB)
        validatePayload(request.operationType(), request.payload());

        // 3. Load document for auth (non-locking read)
        Document document = loadDocumentTimer.record(() ->
                documentRepository.findById(documentId)
                        .orElseThrow(() -> new DocumentNotFoundException(documentId)));
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
        document = lockAcquisitionTimer.record(() ->
                documentRepository.findByIdWithPessimisticLock(documentId)
                        .orElseThrow(() -> new DocumentNotFoundException(documentId)));

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

        // 6. Load intervening operations
        List<DocumentOperation> intervening = loadInterveningOpsTimer.record(() ->
                operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                        documentId, request.baseVersion()));
        if (!intervening.isEmpty()) {
            conflictedCounter.increment();
        }

        // 6a. Pre-parse all intervening op payloads once before the transform loop
        long parseStart = System.nanoTime();
        List<ParsedAcceptedOp> parsed;
        try {
            parsed = intervening.stream()
                    .map(op -> {
                        try {
                            return new ParsedAcceptedOp(op.getOperationType(), objectMapper.readTree(op.getPayload()));
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to deserialize accepted operation payload", e);
                        }
                    })
                    .toList();
        } finally {
            perOpJsonParseTimer.record(System.nanoTime() - parseStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        // 7. Transform incoming op against each intervening op in order
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
            otTransformLoopTimer.record(System.nanoTime() - loopStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        // 8. Apply to document tree; advance cache on every version bump (including NO_OP)
        long currentVersion = document.getCurrentVersion();
        long nextVersion = currentVersion + 1;
        long treeStart = System.nanoTime();
        try {
            final Document lockedDoc = document; // needed: document is re-assigned above (not effectively-final for lambda)
            DocumentTree tree = treeCache.get(documentId, currentVersion)
                    .orElseGet(() -> {
                        try {
                            return objectMapper.readValue(lockedDoc.getContent(), DocumentTree.class);
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to deserialize document tree from content", e);
                        }
                    });
            if (currentType != DocumentOperationType.NO_OP) {
                JsonNode enrichedPayload = tree.applyOperation(currentType, currentPayload);
                currentPayload = enrichedPayload;
                document.setContent(objectMapper.writeValueAsString(tree));
            }
            treeCache.put(documentId, nextVersion, tree);
            treeCache.evict(documentId, currentVersion);
        } catch (Exception e) {
            throw new InvalidOperationException("Failed to apply operation to document: " + e.getMessage(), e);
        } finally {
            treeApplyTimer.record(System.nanoTime() - treeStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        // 9. Persist accepted operation and updated document
        String clientSessionId = headerAccessor != null && headerAccessor.getSessionId() != null
                ? headerAccessor.getSessionId()
                : "";
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

        long persistStart = System.nanoTime();
        try {
            operationRepository.save(accepted);
            document.setCurrentVersion(nextVersion);
            documentRepository.save(document);
        } finally {
            persistOperationTimer.record(System.nanoTime() - persistStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        meterRegistry.counter("operations.accepted", "type", currentType.name()).increment();

        AcceptedOperationResponse acceptedResponse = new AcceptedOperationResponse(
                request.operationId(), documentId, nextVersion,
                currentType, currentPayload, actor.getId(),
                clientSessionId, accepted.getCreatedAt() != null ? accepted.getCreatedAt() : Instant.now());

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
