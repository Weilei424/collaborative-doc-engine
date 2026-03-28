package com.mwang.backend.service;

import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentResponse;

import java.util.List;
import java.util.UUID;

public interface CollaboratorManagementService {
    List<DocumentCollaboratorSummary> listCollaborators(UUID documentId);
    DocumentCollaboratorSummary addCollaborator(UUID documentId, UUID targetUserId, DocumentPermission permission);
    DocumentCollaboratorSummary updateCollaborator(UUID documentId, UUID targetUserId, DocumentPermission permission);
    void removeCollaborator(UUID documentId, UUID targetUserId);
    DocumentResponse transferOwnership(UUID documentId, UUID newOwnerUserId);
}
