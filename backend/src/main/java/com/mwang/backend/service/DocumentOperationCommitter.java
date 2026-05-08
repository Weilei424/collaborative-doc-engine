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
import com.mwang.backend.service.BatchedOpData;
import java.util.ArrayList;
import java.util.List;

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
            // saveAndFlush forces the INSERT within this transaction so the unique-constraint
            // violation is thrown here rather than at commit time (where save() would defer it).
            return operationRepository.saveAndFlush(op);
        } catch (DataIntegrityViolationException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("uk_document_operations_document_operation")) {
                throw new IdempotentOperationException(operationId);
            }
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<DocumentOperation> commitBatch(
            UUID documentId, long expectedVersion, long finalVersion,
            String finalContent, Document document, List<BatchedOpData> ops) {

        int rows = documentRepository.tryAdvanceVersion(
                documentId, expectedVersion, finalVersion, finalContent);
        if (rows == 0) {
            throw new CasMissException();
        }

        List<DocumentOperation> results = new ArrayList<>();
        for (BatchedOpData opData : ops) {
            DocumentOperation op = DocumentOperation.builder()
                    .document(document)
                    .actor(opData.actor())
                    .operationId(opData.operationId())
                    .clientSessionId(opData.clientSessionId())
                    .baseVersion(opData.baseVersion())
                    .serverVersion(opData.serverVersion())
                    .operationType(opData.operationType())
                    .payload(payloadString(opData.payload()))
                    .build();
            try {
                results.add(operationRepository.saveAndFlush(op));
            } catch (DataIntegrityViolationException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("uk_document_operations_document_operation")) {
                    operationRepository.findByDocumentIdAndOperationId(documentId, opData.operationId())
                            .ifPresent(results::add);
                } else {
                    throw e;
                }
            }
        }
        return results;
    }

    private String payloadString(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize payload", e);
        }
    }
}
