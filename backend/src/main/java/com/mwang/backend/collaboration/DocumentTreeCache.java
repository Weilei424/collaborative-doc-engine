package com.mwang.backend.collaboration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mwang.backend.domain.model.DocumentTree;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class DocumentTreeCache {

    private final Cache<TreeCacheKey, DocumentTree> cache;

    public DocumentTreeCache(
            @Value("${collaboration.tree-cache.size:1000}") int maxSize,
            @Value("${collaboration.tree-cache.ttl:300}") int ttlSeconds) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    public Optional<DocumentTree> get(UUID documentId, long serverVersion) {
        DocumentTree cached = cache.getIfPresent(new TreeCacheKey(documentId, serverVersion));
        return Optional.ofNullable(cached).map(DocumentTree::copy);
    }

    public void put(UUID documentId, long serverVersion, DocumentTree tree) {
        // Defensive copy: callers may mutate tree after put() (e.g. writeValueAsString follows);
        // storing a copy ensures the cached entry is never affected by post-put mutations.
        cache.put(new TreeCacheKey(documentId, serverVersion), tree.copy());
    }

    public void evict(UUID documentId, long serverVersion) {
        cache.invalidate(new TreeCacheKey(documentId, serverVersion));
    }

    public record TreeCacheKey(UUID documentId, long serverVersion) {}
}
