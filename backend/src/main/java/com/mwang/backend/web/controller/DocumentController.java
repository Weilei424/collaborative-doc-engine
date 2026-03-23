package com.mwang.backend.web.controller;

import com.mwang.backend.service.DocumentService;
import com.mwang.backend.web.model.CreateDocumentRequest;
import com.mwang.backend.web.model.DocumentPagedList;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.UpdateDocumentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@RestController
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse create(@Valid @RequestBody CreateDocumentRequest request) {
        return documentService.create(request);
    }

    @GetMapping
    public DocumentPagedList list(
            @RequestParam(defaultValue = "accessible") String scope,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return documentService.list(DocumentListScope.from(scope), query, buildPageable(page, size, sort));
    }

    @GetMapping("/{id}")
    public DocumentResponse getById(@PathVariable UUID id) {
        return documentService.getById(id);
    }

    @PutMapping("/{id}")
    public DocumentResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateDocumentRequest request) {
        return documentService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        documentService.delete(id);
    }

    private Pageable buildPageable(int page, int size, String sort) {
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        }

        String[] segments = sort.split(",", 2);
        String property = segments[0].trim();
        Sort.Direction direction = segments.length > 1 ? Sort.Direction.fromOptionalString(segments[1].trim()).orElse(Sort.Direction.ASC) : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, property));
    }
}
