package com.mwang.backend.collaboration;

import java.util.UUID;

public record AccessRevokedEvent(UUID documentId, UUID revokedUserId) {}
