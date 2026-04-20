package com.mwang.backend.repositories;

import com.mwang.backend.domain.DocumentOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

@Repository
public interface DocumentOperationRepository extends JpaRepository<DocumentOperation, UUID> {

    Optional<DocumentOperation> findByDocumentIdAndOperationId(UUID documentId, UUID operationId);

    Optional<DocumentOperation> findTopByDocumentIdOrderByServerVersionDesc(UUID documentId);

    List<DocumentOperation> findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(UUID documentId, Long version);

    List<DocumentOperation> findByDocumentIdAndServerVersionBetweenOrderByServerVersionAsc(UUID documentId, Long from, Long to);

    boolean existsByDocumentIdAndOperationId(UUID documentId, UUID operationId);

    @Query(
        "SELECT o FROM DocumentOperation o JOIN FETCH o.actor JOIN FETCH o.document " +
        "WHERE o.document.id = :documentId AND o.serverVersion > :sinceVersion " +
        "ORDER BY o.serverVersion ASC")
    List<DocumentOperation> findPageAfterVersion(
            @Param("documentId") UUID documentId,
            @Param("sinceVersion") long sinceVersion,
            Pageable pageable);
}
