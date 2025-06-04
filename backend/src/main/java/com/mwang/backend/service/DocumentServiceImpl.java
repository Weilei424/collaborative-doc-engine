package com.mwang.backend.service;

import com.mwang.backend.web.model.DocumentDto;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentServiceImpl implements DocumentService {
    @Override
    public DocumentDto create(DocumentDto documentDto) {
        return null;
    }

    @Override
    public List<DocumentDto> getAll() {
        return List.of();
    }

    @Override
    public Optional<DocumentDto> getById(UUID id) {
        return Optional.empty();
    }

    @Override
    public Optional<DocumentDto> update(UUID id, DocumentDto documentDto) {
        return Optional.empty();
    }

    @Override
    public void delete(UUID id) {

    }
}
