package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.web.model.OperationPageResponse;

import java.util.UUID;

public interface OperationHistoryService {
    OperationPageResponse getOperationPage(UUID documentId, long sinceVersion, int limit, User actor);
}
