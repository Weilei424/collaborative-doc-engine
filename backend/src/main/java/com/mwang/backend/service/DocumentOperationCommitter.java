package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CasMissException;
import com.mwang.backend.service.exception.IdempotentOperationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DocumentOperationCommitter {

    private final DocumentRepository documentRepository;
    private final DocumentOperationRepository operationRepository;
    private final ObjectMapper objectMapper;

    public DocumentOperationCommitter(
            DocumentRepository documentRepository,
            DocumentOperationRepository operationRepository,
            ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentOperation commit(
            UUID documentId, long expectedVersion, long nextVersion,
            String serializedContent, Document document, User actor,
            UUID operationId, String clientSessionId, long baseVersion,
            DocumentOperationType operationType, JsonNode payload) {

        int rows = documentRepository.tryAdvanceVersion(
                documentId, expectedVersion, nextVersion, serializedContent);
        if (rows == 0) {
            throw new CasMissException();
        }

        DocumentOperation op = DocumentOperation.builder()
                .document(document)
                .actor(actor)
                .operationId(operationId)
                .clientSessionId(clientSessionId)
                .baseVersion(baseVersion)
                .serverVersion(nextVersion)
                .operationType(operationType)
                .payload(payloadString(payload))
                .build();

        try {
            return operationRepository.save(op);
        } catch (DataIntegrityViolationException e) {
            throw new IdempotentOperationException(operationId);
        }
    }

    private String payloadString(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize payload", e);
        }
    }
}
