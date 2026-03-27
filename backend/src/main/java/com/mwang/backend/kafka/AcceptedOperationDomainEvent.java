package com.mwang.backend.kafka;

import com.mwang.backend.web.model.AcceptedOperationResponse;

public record AcceptedOperationDomainEvent(
        AcceptedOperationResponse response,
        long baseVersion
) {}
