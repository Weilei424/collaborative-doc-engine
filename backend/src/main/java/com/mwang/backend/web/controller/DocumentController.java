package com.mwang.backend.web.controller;

import com.mwang.backend.service.DocumentService;
import com.mwang.backend.web.model.DocumentDto;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequestMapping("/api/documents")
@AllArgsConstructor
@RestController
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    public DocumentDto create(@RequestBody DocumentDto dto) {
        return documentService.create(dto);
    }

    @GetMapping
    public List<DocumentDto> getAll() {
        return documentService.getAll();
    }

    @GetMapping("/{id}")
    public Optional<DocumentDto> getById(@PathVariable UUID id) {
        return documentService.getById(id);
    }

    @PutMapping("/{id}")
    public Optional<DocumentDto> update(@PathVariable UUID id, @RequestBody DocumentDto dto) {
        return documentService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        documentService.delete(id);
    }
}
