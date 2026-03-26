package com.mwang.backend.service;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentCollaborator;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class DocumentAuthorizationServiceImpl implements DocumentAuthorizationService {

    @Override
    public void assertCanRead(Document document, User actor) {
        if (isOwner(document, actor) || collaboratorEntry(document, actor) != null || document.getVisibility() == DocumentVisibility.PUBLIC) {
            return;
        }

        throw new DocumentAccessDeniedException(document.getId(), actor.getId());
    }

    @Override
    public void assertCanWrite(Document document, User actor) {
        if (isOwner(document, actor)) return;
        DocumentCollaborator collaborator = collaboratorEntry(document, actor);
        if (collaborator != null && collaborator.getPermission().ordinal() >= DocumentPermission.WRITE.ordinal()) return;
        throw new DocumentAccessDeniedException(document.getId(), actor.getId());
    }

    @Override
    public void assertOwner(Document document, User actor) {
        if (!isOwner(document, actor)) {
            throw new DocumentAccessDeniedException(document.getId(), actor.getId());
        }
    }

    @Override
    public String resolveEffectivePermission(Document document, User actor) {
        if (isOwner(document, actor)) {
            return "OWNER";
        }

        DocumentCollaborator collaborator = collaboratorEntry(document, actor);
        if (collaborator != null) {
            return collaborator.getPermission().name();
        }

        if (document.getVisibility() == DocumentVisibility.PUBLIC) {
            return "READ";
        }

        throw new DocumentAccessDeniedException(document.getId(), actor.getId());
    }

    private boolean isOwner(Document document, User actor) {
        return document.getOwner() != null && document.getOwner().equals(actor);
    }

    private DocumentCollaborator collaboratorEntry(Document document, User actor) {
        return document.getCollaborators().stream()
                .filter(collaborator -> collaborator.getUser().equals(actor))
                .findFirst()
                .orElse(null);
    }
}
