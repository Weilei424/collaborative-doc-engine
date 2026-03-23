package com.mwang.backend.service;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.web.controller.DocumentListScope;
import com.mwang.backend.web.mappers.DocumentMapper;
import com.mwang.backend.web.model.CreateDocumentRequest;
import com.mwang.backend.web.model.DocumentPagedList;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.UpdateDocumentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final CurrentUserProvider currentUserProvider;
    private final DocumentAuthorizationService documentAuthorizationService;
    private final DocumentMapper documentMapper;

    @Override
    @Transactional
    public DocumentResponse create(CreateDocumentRequest request) {
        User actor = currentUserProvider.requireCurrentUser();
        Document saved = documentRepository.save(Document.builder()
                .title(request.title())
                .content(request.content())
                .owner(actor)
                .visibility(defaultVisibility(request.visibility()))
                .build());
        return documentMapper.toResponse(saved, "OWNER");
    }

    @Override
    public DocumentPagedList list(DocumentListScope scope, String query, Pageable pageable) {
        User actor = currentUserProvider.requireCurrentUser();
        Page<Document> page = switch (scope) {
            case OWNED -> documentRepository.findOwnedByUserId(actor.getId(), normalizeQuery(query), pageable);
            case SHARED -> documentRepository.findSharedWithUserId(actor.getId(), normalizeQuery(query), pageable);
            case ACCESSIBLE -> documentRepository.findAccessibleByUserId(actor.getId(), normalizeQuery(query), pageable);
            case PUBLIC -> documentRepository.findPublicDocuments(normalizeQuery(query), pageable);
        };

        List<DocumentResponse> items = mapPage(page.getContent(), actor);
        return new DocumentPagedList(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    public DocumentResponse getById(UUID id) {
        User actor = currentUserProvider.requireCurrentUser();
        Document document = documentRepository.findDetailedById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        documentAuthorizationService.assertCanRead(document, actor);
        return documentMapper.toResponse(document, documentAuthorizationService.resolveEffectivePermission(document, actor));
    }

    @Override
    @Transactional
    public DocumentResponse update(UUID id, UpdateDocumentRequest request) {
        User actor = currentUserProvider.requireCurrentUser();
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        documentAuthorizationService.assertOwner(document, actor);

        document.setTitle(request.title());
        document.setContent(request.content());
        document.setVisibility(defaultVisibility(request.visibility()));

        Document saved = documentRepository.save(document);
        return documentMapper.toResponse(saved, "OWNER");
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        User actor = currentUserProvider.requireCurrentUser();
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        documentAuthorizationService.assertOwner(document, actor);
        documentRepository.delete(document);
    }

    private DocumentVisibility defaultVisibility(DocumentVisibility visibility) {
        return visibility == null ? DocumentVisibility.PRIVATE : visibility;
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim();
    }

    private List<DocumentResponse> mapPage(List<Document> documents, User actor) {
        if (documents.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = documents.stream().map(Document::getId).toList();
        LinkedHashMap<UUID, Document> detailedById = documentRepository.findAllDetailedByIdIn(ids).stream()
                .collect(LinkedHashMap::new, (map, document) -> map.put(document.getId(), document), LinkedHashMap::putAll);

        return ids.stream()
                .map(detailedById::get)
                .map(document -> documentMapper.toResponse(document, documentAuthorizationService.resolveEffectivePermission(document, actor)))
                .toList();
    }
}
