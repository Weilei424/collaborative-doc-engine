package com.mwang.backend.web.model;

import com.mwang.backend.domain.DocumentPermission;
import jakarta.validation.constraints.NotNull;

public record UpdateCollaboratorRequest(
        @NotNull(message = "permission is required") DocumentPermission permission
) {}
