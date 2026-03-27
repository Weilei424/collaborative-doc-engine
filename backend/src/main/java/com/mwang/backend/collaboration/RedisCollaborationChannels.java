package com.mwang.backend.collaboration;

import java.util.UUID;

public final class RedisCollaborationChannels {

    public static final String EVENTS = "collaboration:events";
    public static final String DOCUMENT_OPERATIONS_PATTERN = "collaboration:document:*:operations";
    private static final String DOCUMENT_SESSIONS_PREFIX = "collaboration:document:%s:sessions";
    private static final String DOCUMENT_OPERATIONS_PREFIX = "collaboration:document:%s:operations";

    private RedisCollaborationChannels() {
    }

    public static String documentSessions(UUID documentId) {
        return DOCUMENT_SESSIONS_PREFIX.formatted(documentId);
    }

    public static String documentOperations(UUID documentId) {
        return DOCUMENT_OPERATIONS_PREFIX.formatted(documentId);
    }
}
