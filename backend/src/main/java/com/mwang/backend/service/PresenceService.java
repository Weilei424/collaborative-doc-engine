package com.mwang.backend.service;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PresenceService {

    private final Map<UUID, Set<String>> activeUsers = new ConcurrentHashMap<>();

    public void userJoined(UUID documentId, String username) {
        activeUsers.computeIfAbsent(documentId, k -> ConcurrentHashMap.newKeySet()).add(username);
    }

    public void userLeft(UUID documentId, String username) {
        Set<String> users = activeUsers.get(documentId);
        if (users != null) {
            users.remove(username);
            if (users.isEmpty()) {
                activeUsers.remove(documentId);
            }
        }
    }

    public Set<String> getActiveUsers(UUID documentId) {
        return activeUsers.getOrDefault(documentId, Collections.emptySet());
    }
}
