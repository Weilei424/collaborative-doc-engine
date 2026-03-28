package com.mwang.backend.web.model;

import com.mwang.backend.domain.DocumentPermission;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddCollaboratorRequest(
        @NotNull(message = "userId is required") UUID userId,
        @NotNull(message = "permission is required") DocumentPermission permission
) {}
