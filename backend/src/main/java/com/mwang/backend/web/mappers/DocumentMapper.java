package com.mwang.backend.web.mappers;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentCollaborator;
import com.mwang.backend.domain.User;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentOwnerSummary;
import com.mwang.backend.web.model.DocumentResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class DocumentMapper {

    public DocumentResponse toResponse(Document document, String currentUserPermission) {
        return new DocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getContent(),
                document.getVisibility(),
                document.getCurrentVersion(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                toOwnerSummary(document.getOwner()),
                toCollaboratorSummaries(document.getCollaborators().stream().toList()),
                currentUserPermission
        );
    }

    private DocumentOwnerSummary toOwnerSummary(User owner) {
        return new DocumentOwnerSummary(owner.getId(), owner.getUsername());
    }

    private List<DocumentCollaboratorSummary> toCollaboratorSummaries(List<DocumentCollaborator> collaborators) {
        return collaborators.stream()
                .sorted(Comparator.comparing(collaborator -> collaborator.getUser().getUsername()))
                .map(collaborator -> new DocumentCollaboratorSummary(
                        collaborator.getUser().getId(),
                        collaborator.getUser().getUsername(),
                        collaborator.getPermission()))
                .toList();
    }
}
