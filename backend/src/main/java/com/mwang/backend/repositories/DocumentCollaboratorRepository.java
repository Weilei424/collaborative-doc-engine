package com.mwang.backend.repositories;

import com.mwang.backend.domain.DocumentCollaborator;
import com.mwang.backend.domain.DocumentPermission;
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
public interface DocumentCollaboratorRepository extends JpaRepository<DocumentCollaborator, UUID> {

    List<DocumentCollaborator> findByDocumentId(UUID documentId);

    List<DocumentCollaborator> findByUserId(UUID userId);

    Optional<DocumentCollaborator> findByDocumentIdAndUserId(UUID documentId, UUID userId);

    @Query("SELECT dc FROM DocumentCollaborator dc WHERE dc.user.id = :userId AND dc.permission >= :permission")
    Page<DocumentCollaborator> findByUserIdAndPermissionGreaterThanEqual(
            @Param("userId") UUID userId, 
            @Param("permission") DocumentPermission permission, 
            Pageable pageable);

    void deleteByDocumentIdAndUserId(UUID documentId, UUID userId);

    boolean existsByDocumentIdAndUserId(UUID documentId, UUID userId);
}