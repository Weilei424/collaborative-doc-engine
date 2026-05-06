package com.mwang.backend.service;

import com.mwang.backend.web.model.SubmitOperationRequest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.UUID;

public interface DocumentOperationService {
    void submitOperation(
            UUID documentId,
            SubmitOperationRequest request,
            SimpMessageHeaderAccessor headerAccessor);
}
