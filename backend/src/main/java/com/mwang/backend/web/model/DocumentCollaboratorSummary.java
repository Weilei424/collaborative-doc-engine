package com.mwang.backend.web.model;

import com.mwang.backend.domain.DocumentPermission;

import java.util.UUID;

public record DocumentCollaboratorSummary(UUID userId, String username, DocumentPermission permission) {
}
