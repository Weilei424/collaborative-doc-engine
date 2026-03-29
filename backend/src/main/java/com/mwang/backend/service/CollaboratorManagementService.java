package com.mwang.backend.service;

import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

public interface CollaboratorManagementService {
    List<DocumentCollaboratorSummary> listCollaborators(UUID documentId, HttpServletRequest httpRequest);
    DocumentCollaboratorSummary addCollaborator(UUID documentId, UUID targetUserId, DocumentPermission permission, HttpServletRequest httpRequest);
    DocumentCollaboratorSummary updateCollaborator(UUID documentId, UUID targetUserId, DocumentPermission permission, HttpServletRequest httpRequest);
    void removeCollaborator(UUID documentId, UUID targetUserId, HttpServletRequest httpRequest);
    DocumentCollaboratorSummary getCollaborator(UUID documentId, UUID collaboratorUserId, HttpServletRequest httpRequest);
    DocumentResponse transferOwnership(UUID documentId, UUID newOwnerUserId, HttpServletRequest httpRequest);
}
