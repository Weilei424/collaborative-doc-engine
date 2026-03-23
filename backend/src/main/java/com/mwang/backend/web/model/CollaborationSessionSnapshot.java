package com.mwang.backend.web.model;

import java.util.List;
import java.util.UUID;

public record CollaborationSessionSnapshot(UUID documentId, List<CollaborationSessionResponse> sessions) {
}
