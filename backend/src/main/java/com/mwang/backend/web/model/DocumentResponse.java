package com.mwang.backend.web.model;

import com.mwang.backend.domain.DocumentVisibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String title,
        String content,
        DocumentVisibility visibility,
        long currentVersion,
        Instant createdAt,
        Instant updatedAt,
        DocumentOwnerSummary owner,
        List<DocumentCollaboratorSummary> collaborators,
        String currentUserPermission) {
}
