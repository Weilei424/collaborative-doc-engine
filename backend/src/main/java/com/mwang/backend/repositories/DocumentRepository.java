package com.mwang.backend.repositories;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<Document> findByOwnerIdAndTitleContainingIgnoreCase(UUID ownerId, String title, Pageable pageable);

    List<Document> findByVisibility(DocumentVisibility visibility);

    @Query("SELECT d FROM Document d WHERE d.owner.id = :userId OR " +
           "EXISTS (SELECT dc FROM DocumentCollaborator dc WHERE dc.document.id = d.id AND dc.user.id = :userId)")
    Page<Document> findDocumentsAccessibleByUser(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE (d.owner.id = :userId OR " +
           "EXISTS (SELECT dc FROM DocumentCollaborator dc WHERE dc.document.id = d.id AND dc.user.id = :userId)) " +
           "AND LOWER(d.title) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<Document> findDocumentsAccessibleByUserWithSearch(
            @Param("userId") UUID userId, 
            @Param("searchTerm") String searchTerm, 
            Pageable pageable);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Document> findByIdAndOwnerId(UUID id, UUID ownerId);
}
