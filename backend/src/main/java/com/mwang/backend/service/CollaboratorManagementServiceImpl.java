package com.mwang.backend.service;

import com.mwang.backend.collaboration.AccessRevokedEvent;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentCollaborator;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentCollaboratorRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.CollaboratorAlreadyExistsException;
import com.mwang.backend.service.exception.CollaboratorNotFoundException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.service.exception.UserNotFoundException;
import com.mwang.backend.web.mappers.DocumentMapper;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CollaboratorManagementServiceImpl implements CollaboratorManagementService {

    private final DocumentRepository documentRepository;
    private final DocumentCollaboratorRepository collaboratorRepository;
    private final UserRepository userRepository;
    private final DocumentAuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final DocumentMapper documentMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public DocumentCollaboratorSummary getCollaborator(UUID documentId, UUID collaboratorUserId, HttpServletRequest httpRequest) {
        User actor = currentUserProvider.requireCurrentUser(httpRequest);
        Document document = requireDocument(documentId);
        authorizationService.assertCanRead(document, actor);
        DocumentCollaborator collab = collaboratorRepository
                .findByDocumentIdAndUserId(documentId, collaboratorUserId)
                .orElseThrow(() -> new CollaboratorNotFoundException(documentId, collaboratorUserId));
        return new DocumentCollaboratorSummary(
                collab.getUser().getId(),
                collab.getUser().getUsername(),
                collab.getPermission());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentCollaboratorSummary> listCollaborators(UUID documentId, HttpServletRequest httpRequest) {
        User actor = currentUserProvider.requireCurrentUser(httpRequest);
        Document document = requireDocument(documentId);
        authorizationService.assertCanRead(document, actor);
        return document.getCollaborators().stream()
                .map(c -> new DocumentCollaboratorSummary(
                        c.getUser().getId(), c.getUser().getUsername(), c.getPermission()))
                .toList();
    }

    @Override
    @Transactional
    public DocumentCollaboratorSummary addCollaborator(
            UUID documentId, UUID targetUserId, DocumentPermission permission, HttpServletRequest httpRequest) {
        User actor = currentUserProvider.requireCurrentUser(httpRequest);
        Document document = requireDocument(documentId);
        authorizationService.assertCanAdmin(document, actor);

        User target = requireUser(targetUserId);
        if (document.getOwner().equals(target)) {
            throw new InvalidCollaborationRequestException(
                    "Cannot add the document owner as a collaborator");
        }
        if (collaboratorRepository.existsByDocumentIdAndUserId(documentId, targetUserId)) {
            throw new CollaboratorAlreadyExistsException(documentId, targetUserId);
        }

        DocumentCollaborator entry = DocumentCollaborator.builder()
                .document(document)
                .user(target)
                .permission(permission)
                .build();
        collaboratorRepository.save(entry);
        return new DocumentCollaboratorSummary(target.getId(), target.getUsername(), permission);
    }

    @Override
    @Transactional
    public DocumentCollaboratorSummary updateCollaborator(
            UUID documentId, UUID targetUserId, DocumentPermission permission, HttpServletRequest httpRequest) {
        User actor = currentUserProvider.requireCurrentUser(httpRequest);
        Document document = requireDocument(documentId);
        authorizationService.assertCanAdmin(document, actor);

        DocumentCollaborator entry = collaboratorRepository
                .findByDocumentIdAndUserId(documentId, targetUserId)
                .orElseThrow(() -> new CollaboratorNotFoundException(documentId, targetUserId));
        entry.setPermission(permission);
        collaboratorRepository.save(entry);
        return new DocumentCollaboratorSummary(
                entry.getUser().getId(), entry.getUser().getUsername(), permission);
    }

    @Override
    @Transactional
    public void removeCollaborator(UUID documentId, UUID targetUserId, HttpServletRequest httpRequest) {
        User actor = currentUserProvider.requireCurrentUser(httpRequest);
        Document document = requireDocument(documentId);
        authorizationService.assertCanAdmin(document, actor);

        if (document.getOwner().getId().equals(targetUserId)) {
            throw new InvalidCollaborationRequestException("Cannot remove the document owner");
        }
        if (!collaboratorRepository.existsByDocumentIdAndUserId(documentId, targetUserId)) {
            throw new CollaboratorNotFoundException(documentId, targetUserId);
        }
        collaboratorRepository.deleteByDocumentIdAndUserId(documentId, targetUserId);
        eventPublisher.publishEvent(new AccessRevokedEvent(documentId, targetUserId));
    }

    @Override
    @Transactional
    public DocumentResponse transferOwnership(UUID documentId, UUID newOwnerUserId, HttpServletRequest httpRequest) {
        User actor = currentUserProvider.requireCurrentUser(httpRequest);
        Document document = requireDocument(documentId);
        authorizationService.assertOwner(document, actor);

        DocumentCollaborator newOwnerEntry = document.getCollaborators().stream()
                .filter(c -> c.getUser().getId().equals(newOwnerUserId))
                .findFirst()
                .orElseThrow(() -> new CollaboratorNotFoundException(documentId, newOwnerUserId));

        User newOwner = newOwnerEntry.getUser();
        User oldOwner = document.getOwner();

        // Remove new owner from collaborators (orphanRemoval handles DELETE on save)
        document.getCollaborators().remove(newOwnerEntry);

        // Add old owner as ADMIN collaborator
        document.addCollaborator(oldOwner, DocumentPermission.ADMIN);

        // Update ownership and persist all changes in one save
        document.setOwner(newOwner);
        documentRepository.save(document);
        eventPublisher.publishEvent(new AccessRevokedEvent(documentId, oldOwner.getId()));

        Document refreshed = requireDocument(documentId);
        return documentMapper.toResponse(refreshed, "OWNER");
    }

    private Document requireDocument(UUID documentId) {
        return documentRepository.findDetailedById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
