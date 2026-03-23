package com.mwang.backend.collaboration;

import java.util.UUID;

public final class RedisCollaborationChannels {

    public static final String EVENTS = "collaboration:events";
    private static final String DOCUMENT_SESSIONS_PREFIX = "collaboration:document:%s:sessions";

    private RedisCollaborationChannels() {
    }

    public static String documentSessions(UUID documentId) {
        return DOCUMENT_SESSIONS_PREFIX.formatted(documentId);
    }
}
