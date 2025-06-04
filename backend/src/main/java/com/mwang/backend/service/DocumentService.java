package com.mwang.backend.service;

import com.mwang.backend.web.model.DocumentDto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentService {
    DocumentDto create(DocumentDto documentDto);
    List<DocumentDto> getAll();
    Optional<DocumentDto> getById(UUID id);
    Optional<DocumentDto> update(UUID id, DocumentDto documentDto);
    void delete(UUID id);
}
