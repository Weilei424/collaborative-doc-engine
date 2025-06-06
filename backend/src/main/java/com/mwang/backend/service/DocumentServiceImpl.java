package com.mwang.backend.service;

import com.mwang.backend.domain.Document;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.web.mappers.DocumentMapper;
import com.mwang.backend.web.model.DocumentDto;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository repository;
    private final DocumentMapper mapper;

    @Override
    public DocumentDto create(DocumentDto documentDto) {
        Document saved = repository.save(mapper.documentDtoToDocument(documentDto));
        return mapper.documentToDocumentDto(saved);
    }

    @Override
    public List<DocumentDto> getAll() {
        List<DocumentDto> list = new ArrayList<>();
        repository.findAll().forEach(doc -> list.add(mapper.documentToDocumentDto(doc)));
        return list;
    }

    @Override
    public Optional<DocumentDto> getById(UUID id) {
        return repository.findById(id).map(mapper::documentToDocumentDto);
    }

    @Override
    public Optional<DocumentDto> update(UUID id, DocumentDto documentDto) {
        return repository.findById(id).map(existing -> {
            existing.setTitle(documentDto.getTitle());
            existing.setContent(documentDto.getContent());
            return mapper.documentToDocumentDto(repository.save(existing));
        });
    }

    @Override
    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
