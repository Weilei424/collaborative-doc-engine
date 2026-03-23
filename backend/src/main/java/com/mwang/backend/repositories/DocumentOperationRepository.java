package com.mwang.backend.repositories;

import com.mwang.backend.domain.DocumentOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentOperationRepository extends JpaRepository<DocumentOperation, UUID> {

    Optional<DocumentOperation> findByDocumentIdAndOperationId(UUID documentId, UUID operationId);

    Optional<DocumentOperation> findTopByDocumentIdOrderByServerVersionDesc(UUID documentId);

    List<DocumentOperation> findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(UUID documentId, Long version);

    List<DocumentOperation> findByDocumentIdAndServerVersionBetweenOrderByServerVersionAsc(UUID documentId, Long from, Long to);

    boolean existsByDocumentIdAndOperationId(UUID documentId, UUID operationId);
}
