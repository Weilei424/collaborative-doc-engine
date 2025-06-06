package com.mwang.backend.documentservice.web.controller;

import com.mwang.backend.documentservice.service.DocumentService;
import com.mwang.backend.documentservice.web.model.DocumentDto;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequestMapping("/api/documents")
@AllArgsConstructor
@Controller
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
