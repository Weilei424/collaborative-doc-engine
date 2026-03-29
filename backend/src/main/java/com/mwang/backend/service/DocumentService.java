package com.mwang.backend.service;

import com.mwang.backend.web.controller.DocumentListScope;
import com.mwang.backend.web.model.CreateDocumentRequest;
import com.mwang.backend.web.model.DocumentPagedList;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.UpdateDocumentRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DocumentService {
    DocumentResponse create(CreateDocumentRequest request, HttpServletRequest httpRequest);
    DocumentPagedList list(DocumentListScope scope, String query, Pageable pageable, HttpServletRequest httpRequest);
    DocumentResponse getById(UUID id, HttpServletRequest httpRequest);
    DocumentResponse update(UUID id, UpdateDocumentRequest request, HttpServletRequest httpRequest);
    void delete(UUID id, HttpServletRequest httpRequest);
}
