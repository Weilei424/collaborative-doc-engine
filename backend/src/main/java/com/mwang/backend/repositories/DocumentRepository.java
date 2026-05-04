package com.mwang.backend.repositories;

import com.mwang.backend.domain.Document;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Query("""
            SELECT d FROM Document d
            WHERE d.owner.id = :userId
              AND (:query IS NULL OR TRIM(:query) = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<Document> findOwnedByUserId(@Param("userId") UUID userId, @Param("query") String query, Pageable pageable);

    @Query("""
            SELECT d FROM Document d
            WHERE EXISTS (
                SELECT dc FROM DocumentCollaborator dc
                WHERE dc.document = d
                  AND dc.user.id = :userId
            )
              AND d.owner.id <> :userId
              AND (:query IS NULL OR TRIM(:query) = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<Document> findSharedWithUserId(@Param("userId") UUID userId, @Param("query") String query, Pageable pageable);

    @Query("""
            SELECT d FROM Document d
            WHERE d.visibility = com.mwang.backend.domain.DocumentVisibility.PUBLIC
              AND (:query IS NULL OR TRIM(:query) = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<Document> findPublicDocuments(@Param("query") String query, Pageable pageable);

    @Query("""
            SELECT DISTINCT d FROM Document d
            LEFT JOIN d.collaborators dc
            WHERE (d.owner.id = :userId
                OR dc.user.id = :userId
                OR d.visibility = com.mwang.backend.domain.DocumentVisibility.PUBLIC)
              AND (:query IS NULL OR TRIM(:query) = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<Document> findAccessibleByUserId(@Param("userId") UUID userId, @Param("query") String query, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "collaborators", "collaborators.user"})
    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN d.collaborators dc LEFT JOIN dc.user WHERE d.id = :id")
    Optional<Document> findDetailedById(@Param("id") UUID id);

    @Query("""
            SELECT DISTINCT d FROM Document d
            LEFT JOIN FETCH d.owner
            LEFT JOIN FETCH d.collaborators dc
            LEFT JOIN FETCH dc.user
            WHERE d.id IN :ids
            """)
    List<Document> findAllDetailedByIdIn(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :id")
    Optional<Document> findByIdWithPessimisticLock(@Param("id") UUID id);

    @Modifying
    @Query("""
            UPDATE Document d
            SET d.currentVersion = :nextVersion, d.content = :content,
                d.version = d.version + 1, d.updatedAt = CURRENT_TIMESTAMP
            WHERE d.id = :documentId AND d.currentVersion = :expectedVersion
            """)
    int tryAdvanceVersion(
            @Param("documentId") UUID documentId,
            @Param("expectedVersion") long expectedVersion,
            @Param("nextVersion") long nextVersion,
            @Param("content") String content
    );
}
