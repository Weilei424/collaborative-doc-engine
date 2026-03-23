package com.mwang.backend.service;

import com.mwang.backend.web.controller.DocumentListScope;
import com.mwang.backend.web.model.CreateDocumentRequest;
import com.mwang.backend.web.model.DocumentPagedList;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.UpdateDocumentRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DocumentService {
    DocumentResponse create(CreateDocumentRequest request);
    DocumentPagedList list(DocumentListScope scope, String query, Pageable pageable);
    DocumentResponse getById(UUID id);
    DocumentResponse update(UUID id, UpdateDocumentRequest request);
    void delete(UUID id);
}
