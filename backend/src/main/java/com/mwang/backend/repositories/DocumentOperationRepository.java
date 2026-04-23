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
            WITH doc_candidates AS (
              SELECT o.document_id
              FROM document_operations o
              WHERE o.published_to_kafka_at IS NULL
                AND o.kafka_poison_at IS NULL
                AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= :now)
                AND NOT EXISTS (
                  SELECT 1 FROM document_operations blocker
                  WHERE blocker.document_id = o.document_id
                    AND blocker.published_to_kafka_at IS NULL
                    AND blocker.kafka_poison_at IS NULL
                    AND blocker.server_version < o.server_version
                    AND blocker.next_attempt_at > :now
                )
              GROUP BY o.document_id
              ORDER BY MIN(o.next_attempt_at) ASC NULLS FIRST, MIN(o.server_version) ASC
              LIMIT :limit
            ),
            locked_docs AS (
              SELECT document_id
              FROM doc_candidates
              WHERE pg_try_advisory_xact_lock(hashtext(document_id::text)::bigint)
            )
            SELECT o.* FROM document_operations o
            JOIN locked_docs ld ON ld.document_id = o.document_id
            WHERE o.published_to_kafka_at IS NULL
              AND o.kafka_poison_at IS NULL
              AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= :now)
              AND NOT EXISTS (
                SELECT 1 FROM document_operations blocker
                WHERE blocker.document_id = o.document_id
                  AND blocker.published_to_kafka_at IS NULL
                  AND blocker.kafka_poison_at IS NULL
                  AND blocker.server_version < o.server_version
                  AND blocker.next_attempt_at > :now
              )
            ORDER BY o.next_attempt_at ASC NULLS FIRST, o.server_version ASC
            LIMIT :limit
            FOR UPDATE OF o SKIP LOCKED
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
