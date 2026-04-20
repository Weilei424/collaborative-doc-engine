package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.OperationPageResponse;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OperationHistoryServiceImpl implements OperationHistoryService {

    private static final int MAX_LIMIT = 2000;

    private final DocumentRepository documentRepository;
    private final DocumentOperationRepository operationRepository;
    private final DocumentAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final int rateLimitPerMinute;

    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastAccess = new ConcurrentHashMap<>();

    public OperationHistoryServiceImpl(
            DocumentRepository documentRepository,
            DocumentOperationRepository operationRepository,
            DocumentAuthorizationService authorizationService,
            ObjectMapper objectMapper,
            @Value("${collaboration.resync.rate-limit:30}") int rateLimitPerMinute) {
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    @Override
    @Transactional(readOnly = true)
    public OperationPageResponse getOperationPage(UUID documentId, long sinceVersion, int limit, User actor) {
        if (actor == null) {
            throw new UserContextRequiredException();
        }
        var document = documentRepository.findDetailedById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        authorizationService.assertCanRead(document, actor);

        String bucketKey = "resync:" + actor.getId() + ":" + documentId;
        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(bucketKey, k ->
                RateLimiter.of("resync-rate-limiter", RateLimiterConfig.custom()
                        .limitForPeriod(rateLimitPerMinute)
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO)
                        .build()));
        lastAccess.put(bucketKey, Instant.now());
        if (!rateLimiter.acquirePermission()) {
            throw io.github.resilience4j.ratelimiter.RequestNotPermitted.createRequestNotPermitted(rateLimiter);
        }

        int effectiveLimit = Math.min(limit, MAX_LIMIT);
        List<DocumentOperation> fetched = operationRepository.findPageAfterVersion(
                documentId, sinceVersion, PageRequest.of(0, effectiveLimit + 1));

        boolean hasMore = fetched.size() > effectiveLimit;
        List<DocumentOperation> ops = hasMore ? fetched.subList(0, effectiveLimit) : fetched;

        List<AcceptedOperationResponse> responses = ops.stream()
                .map(op -> toResponse(op, documentId))
                .toList();

        return new OperationPageResponse(documentId, sinceVersion, responses, hasMore);
    }

    @Scheduled(fixedDelay = 60_000)
    void evictIdleRateLimiters() {
        Instant cutoff = Instant.now().minusSeconds(300);
        lastAccess.forEach((key, ts) -> {
            if (ts.isBefore(cutoff) && lastAccess.remove(key, ts)) {
                // CAS: only remove if value is still `ts` — prevents orphaning
                // a limiter that was just refreshed by a concurrent request
                rateLimiters.remove(key);
            }
        });
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
}
