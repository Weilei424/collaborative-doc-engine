package com.mwang.backend.web.model;

import java.util.List;
import java.util.UUID;

public record OperationPageResponse(
        UUID documentId,
        long sinceVersion,
        List<AcceptedOperationResponse> operations,
        boolean hasMore
) {
    public OperationPageResponse {
        operations = operations != null ? List.copyOf(operations) : List.of();
    }
}
