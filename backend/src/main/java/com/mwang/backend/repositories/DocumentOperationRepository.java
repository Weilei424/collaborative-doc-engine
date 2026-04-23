package com.mwang.backend.repositories;

import com.mwang.backend.domain.DocumentOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    @Query(value = """
            SELECT * FROM document_operations
            WHERE published_to_kafka_at IS NULL
              AND kafka_poison_at IS NULL
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY next_attempt_at ASC NULLS FIRST, server_version ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DocumentOperation> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM document_operations WHERE published_to_kafka_at IS NULL AND kafka_poison_at IS NULL",
            nativeQuery = true)
    long countPending();

    @Query(value = "SELECT COUNT(*) FROM document_operations WHERE kafka_poison_at IS NOT NULL",
            nativeQuery = true)
    long countPoison();

    @Modifying
    @Transactional
    @Query("UPDATE DocumentOperation o SET o.publishedToKafkaAt = :at, o.kafkaPublishAttempts = o.kafkaPublishAttempts + 1, o.kafkaLastError = null WHERE o.id = :id")
    void markPublished(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("UPDATE DocumentOperation o SET o.kafkaPublishAttempts = :attempts, o.kafkaLastError = :error, o.nextAttemptAt = :nextAt WHERE o.id = :id")
    void recordFailure(@Param("id") UUID id, @Param("attempts") int attempts, @Param("error") String error, @Param("nextAt") Instant nextAt);

    @Modifying
    @Transactional
    @Query("UPDATE DocumentOperation o SET o.kafkaPoisonAt = :at, o.kafkaPublishAttempts = :attempts, o.kafkaLastError = :error WHERE o.id = :id")
    void markPoison(@Param("id") UUID id, @Param("at") Instant at, @Param("attempts") int attempts, @Param("error") String error);
}
