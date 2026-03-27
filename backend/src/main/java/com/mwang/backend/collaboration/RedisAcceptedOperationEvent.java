package com.mwang.backend.collaboration;

import com.mwang.backend.web.model.AcceptedOperationResponse;

public record RedisAcceptedOperationEvent(
        String publisherInstanceId,
        AcceptedOperationResponse payload) {
}
