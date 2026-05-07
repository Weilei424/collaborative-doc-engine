package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.DocumentTreeCache;
import com.mwang.backend.collaboration.OperationTransformer;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.model.DocumentTree;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CasMissException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.OperationErrorResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DocumentOperationBatcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentOperationBatcher.class);

    private final DocumentRepository documentRepository;
    private final DocumentOperationRepository operationRepository;
    private final DocumentOperationCommitter committer;
    private final DocumentAuthorizationService authorizationService;
    private final OperationTransformer transformer;
    private final ObjectMapper objectMapper;
    private final DocumentTreeCache treeCache;
    private final CollaborationBroadcastService broadcastService;
    private final RedisCollaborationEventPublisher redisPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final MeterRegistry meterRegistry;
    private final int staleCap;
    private final int maxCasRetries;
    private final int maxBatchSize;
    private final long windowMs;
    private final long shutdownDrainTimeoutMs;
    private final DistributionSummary batchSizeSummary;

    private final ConcurrentHashMap<UUID, LinkedBlockingQueue<PendingOperation>> queues =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicBoolean> drainScheduled =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "op-batcher-scheduler"));

    private record AccOp(long serverVersion, DocumentOperationType type, JsonNode payload, UUID operationId) {}

    public DocumentOperationBatcher(
            DocumentRepository documentRepository,
            DocumentOperationRepository operationRepository,
            DocumentOperationCommitter committer,
            DocumentAuthorizationService authorizationService,
            OperationTransformer transformer,
            ObjectMapper objectMapper,
            DocumentTreeCache treeCache,
            CollaborationBroadcastService broadcastService,
            RedisCollaborationEventPublisher redisPublisher,
            SimpMessagingTemplate messagingTemplate,
            MeterRegistry meterRegistry,
            @Value("${collaboration.stale-cap:200}") int staleCap,
            @Value("${collaboration.batch.max-cas-retries:3}") int maxCasRetries,
            @Value("${collaboration.batch.max-size:50}") int maxBatchSize,
            @Value("${collaboration.batch.window-ms:5}") long windowMs,
            @Value("${collaboration.batch.shutdown-drain-timeout-ms:5000}") long shutdownDrainTimeoutMs) {
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
        this.committer = committer;
        this.authorizationService = authorizationService;
        this.transformer = transformer;
        this.objectMapper = objectMapper;
        this.treeCache = treeCache;
        this.broadcastService = broadcastService;
        this.redisPublisher = redisPublisher;
        this.messagingTemplate = messagingTemplate;
        this.meterRegistry = meterRegistry;
        this.staleCap = staleCap;
        this.maxCasRetries = maxCasRetries;
        this.maxBatchSize = maxBatchSize;
        this.windowMs = windowMs;
        this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs;
        this.batchSizeSummary = DistributionSummary.builder("operations.batch.size")
                .description("Number of ops committed in a single drain cycle")
                .register(meterRegistry);
    }

    public void enqueue(PendingOperation op) {
        UUID docId = op.documentId();
        queues.computeIfAbsent(docId, id -> new LinkedBlockingQueue<>()).offer(op);
        AtomicBoolean flag = drainScheduled.computeIfAbsent(docId, id -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            scheduler.schedule(() -> drain(docId), windowMs, TimeUnit.MILLISECONDS);
        }
    }

    void drain(UUID documentId) {
        AtomicBoolean flag = drainScheduled.get(documentId);
        if (flag != null) flag.set(false);

        LinkedBlockingQueue<PendingOperation> queue = queues.get(documentId);
        if (queue == null || queue.isEmpty()) return;

        List<PendingOperation> batch = new ArrayList<>(maxBatchSize);
        queue.drainTo(batch, maxBatchSize);
        if (batch.isEmpty()) return;

        batchSizeSummary.record(batch.size());
        processBatch(documentId, batch);

        // If more ops arrived (or were pre-queued beyond maxBatchSize), schedule a follow-up drain.
        if (!queue.isEmpty() && flag != null && flag.compareAndSet(false, true)) {
            scheduler.schedule(() -> drain(documentId), windowMs, TimeUnit.MILLISECONDS);
        }
    }

    private void processBatch(UUID documentId, List<PendingOperation> batch) {
        for (int attempt = 1; attempt <= maxCasRetries; attempt++) {
            try {
                attemptCommit(documentId, batch);
                return;
            } catch (CasMissException e) {
                meterRegistry.counter("operations.retries", "attempt", String.valueOf(attempt)).increment();
                if (attempt == maxCasRetries) {
                    deliverConflictToAll(documentId, batch);
                }
            } catch (Exception e) {
                log.error("Unexpected error processing batch for document {}", documentId, e);
                deliverConflictToAll(documentId, batch);
                return;
            }
        }
    }

    private void attemptCommit(UUID documentId, List<PendingOperation> batch) {
        Document document = documentRepository.findDetailedById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        long minBaseVersion = batch.stream()
                .mapToLong(op -> op.request().baseVersion())
                .min().orElseThrow();

        List<DocumentOperation> serverOps = operationRepository
                .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(documentId, minBaseVersion);

        List<AccOp> accumulated = new ArrayList<>();
        for (DocumentOperation sop : serverOps) {
            try {
                accumulated.add(new AccOp(sop.getServerVersion(), sop.getOperationType(),
                        objectMapper.readTree(sop.getPayload()), sop.getOperationId()));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse server op payload", e);
            }
        }

        long expectedVersion = document.getCurrentVersion();
        DocumentTree tree = treeCache.get(documentId, expectedVersion)
                .orElseGet(() -> {
                    try {
                        return objectMapper.readValue(document.getContent(), DocumentTree.class);
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to parse document tree", e);
                    }
                });

        long serverVersion = expectedVersion;
        List<BatchedOpData> toCommit = new ArrayList<>();
        List<PendingOperation> staleOps = new ArrayList<>();

        for (PendingOperation pending : batch) {
            SubmitOperationRequest req = pending.request();

            try {
                authorizationService.assertCanWrite(document, pending.actor());
            } catch (RuntimeException e) {
                deliverError(pending, documentId, "OPERATION_CONFLICT", null);
                continue;
            }

            Optional<DocumentOperation> duplicate = serverOps.stream()
                    .filter(sop -> sop.getOperationId().equals(req.operationId()))
                    .findFirst();
            if (duplicate.isPresent()) {
                AcceptedOperationResponse response = toResponse(duplicate.get(), documentId);
                broadcastService.broadcastAcceptedOperation(documentId, response);
                redisPublisher.publishAcceptedOperation(documentId, response);
                continue;
            }

            if (document.getCurrentVersion() - req.baseVersion() > staleCap) {
                staleOps.add(pending);
                continue;
            }

            // Within-batch dedup: if an earlier op in this batch has the same operationId,
            // skip this one silently (the earlier op's broadcast covers both)
            boolean inBatchDuplicate = toCommit.stream()
                    .anyMatch(existing -> existing.operationId().equals(req.operationId()));
            if (inBatchDuplicate) {
                continue;
            }

            DocumentOperationType currentType = req.operationType();
            JsonNode currentPayload = req.payload();
            for (AccOp acc : accumulated) {
                if (acc.serverVersion() > req.baseVersion()) {
                    Optional<JsonNode> transformed = transformer.transform(
                            currentType, currentPayload, acc.type(), acc.payload());
                    if (transformed.isEmpty()) {
                        currentType = DocumentOperationType.NO_OP;
                        currentPayload = objectMapper.createObjectNode();
                        break;
                    }
                    currentPayload = transformed.get();
                }
            }

            if (currentType != DocumentOperationType.NO_OP) {
                tree.applyOperation(currentType, currentPayload);
            }
            serverVersion++;

            toCommit.add(new BatchedOpData(
                    req.operationId(), pending.clientSessionId(), req.baseVersion(),
                    currentType, currentPayload, pending.actor(), serverVersion));

            accumulated.add(new AccOp(serverVersion, currentType, currentPayload, req.operationId()));
        }

        if (!toCommit.isEmpty()) {
            String finalContent;
            try {
                finalContent = objectMapper.writeValueAsString(tree);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize document tree", e);
            }

            List<DocumentOperation> committed = committer.commitBatch(
                    documentId, expectedVersion, serverVersion, finalContent, document, toCommit);

            treeCache.put(documentId, serverVersion, tree);
            treeCache.evict(documentId, expectedVersion);

            for (DocumentOperation op : committed) {
                AcceptedOperationResponse response = toResponse(op, documentId);
                broadcastService.broadcastAcceptedOperation(documentId, response);
                redisPublisher.publishAcceptedOperation(documentId, response);
                meterRegistry.counter("operations.accepted", "type", op.getOperationType().name()).increment();
            }
        }

        for (PendingOperation stale : staleOps) {
            meterRegistry.counter("operations.resync_required").increment();
            deliverError(stale, documentId, "RESYNC_REQUIRED", document.getCurrentVersion());
        }
    }

    private void deliverError(PendingOperation op, UUID documentId, String errorType, Long serverVersion) {
        messagingTemplate.convertAndSendToUser(
                op.principalName(), "/queue/errors." + documentId,
                new OperationErrorResponse(errorType, op.request().operationId(), serverVersion));
    }

    private void deliverConflictToAll(UUID documentId, List<PendingOperation> batch) {
        for (PendingOperation op : batch) {
            deliverError(op, documentId, "OPERATION_CONFLICT", null);
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

    @PreDestroy
    public void shutdown() {
        long deadline = System.currentTimeMillis() + shutdownDrainTimeoutMs;
        for (UUID docId : new ArrayList<>(queues.keySet())) {
            LinkedBlockingQueue<PendingOperation> queue = queues.get(docId);
            if (queue == null || queue.isEmpty()) continue;
            if (System.currentTimeMillis() < deadline) {
                drain(docId);
            } else {
                List<PendingOperation> remaining = new ArrayList<>();
                queue.drainTo(remaining);
                deliverConflictToAll(docId, remaining);
            }
        }
        scheduler.shutdownNow();
    }
}
