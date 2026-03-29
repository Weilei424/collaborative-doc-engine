package com.mwang.backend.service;

import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.UUID;

public interface DocumentOperationService {
    AcceptedOperationResponse submitOperation(
            UUID documentId,
            SubmitOperationRequest request,
            SimpMessageHeaderAccessor headerAccessor);
}
