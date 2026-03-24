package com.mwang.backend.service;

import com.mwang.backend.web.model.CollaborationSessionResponse;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CollaborationSessionTracking {

    private static final String TRACKED_SESSIONS_ATTRIBUTE = CollaborationSessionTracking.class.getName() + ".trackedSessions";

    private CollaborationSessionTracking() {
    }

    static void track(Map<String, Object> sessionAttributes, CollaborationSessionResponse session) {
        if (sessionAttributes == null) {
            return;
        }

        LinkedHashSet<TrackedSessionRef> trackedSessions = mutableTrackedSessions(sessionAttributes);
        trackedSessions.add(new TrackedSessionRef(session.documentId(), session.sessionId()));
        sessionAttributes.put(TRACKED_SESSIONS_ATTRIBUTE, trackedSessions);
    }

    static void untrack(Map<String, Object> sessionAttributes, UUID documentId, UUID sessionId) {
        if (sessionAttributes == null) {
            return;
        }

        LinkedHashSet<TrackedSessionRef> trackedSessions = mutableTrackedSessions(sessionAttributes);
        trackedSessions.remove(new TrackedSessionRef(documentId, sessionId));
        if (trackedSessions.isEmpty()) {
            sessionAttributes.remove(TRACKED_SESSIONS_ATTRIBUTE);
            return;
        }

        sessionAttributes.put(TRACKED_SESSIONS_ATTRIBUTE, trackedSessions);
    }

    static List<TrackedSessionRef> trackedSessions(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            return List.of();
        }

        Object rawTrackedSessions = sessionAttributes.get(TRACKED_SESSIONS_ATTRIBUTE);
        if (!(rawTrackedSessions instanceof Collection<?> collection)) {
            return List.of();
        }

        return collection.stream()
                .filter(TrackedSessionRef.class::isInstance)
                .map(TrackedSessionRef.class::cast)
                .toList();
    }

    static boolean isTracked(Map<String, Object> sessionAttributes, UUID documentId, UUID sessionId) {
        return trackedSessions(sessionAttributes).contains(new TrackedSessionRef(documentId, sessionId));
    }

    static void clear(Map<String, Object> sessionAttributes) {
        if (sessionAttributes != null) {
            sessionAttributes.remove(TRACKED_SESSIONS_ATTRIBUTE);
        }
    }

    private static LinkedHashSet<TrackedSessionRef> mutableTrackedSessions(Map<String, Object> sessionAttributes) {
        Object rawTrackedSessions = sessionAttributes.get(TRACKED_SESSIONS_ATTRIBUTE);
        if (rawTrackedSessions instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(TrackedSessionRef.class::isInstance)
                    .map(TrackedSessionRef.class::cast)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        return new LinkedHashSet<>();
    }

    record TrackedSessionRef(UUID documentId, UUID sessionId) {
    }
}
