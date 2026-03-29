package com.mwang.backend.web.controller;

import com.mwang.backend.service.CollaboratorManagementService;
import com.mwang.backend.web.model.AddCollaboratorRequest;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.TransferOwnershipRequest;
import com.mwang.backend.web.model.UpdateCollaboratorRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/documents/{documentId}/collaborators")
@RequiredArgsConstructor
public class CollaboratorController {

    private final CollaboratorManagementService collaboratorManagementService;

    @GetMapping
    public List<DocumentCollaboratorSummary> listCollaborators(@PathVariable UUID documentId, HttpServletRequest httpRequest) {
        return collaboratorManagementService.listCollaborators(documentId, httpRequest);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentCollaboratorSummary addCollaborator(
            @PathVariable UUID documentId,
            @Valid @RequestBody AddCollaboratorRequest request,
            HttpServletRequest httpRequest) {
        return collaboratorManagementService.addCollaborator(
                documentId, request.userId(), request.permission(), httpRequest);
    }

    @GetMapping("/{userId}")
    public DocumentCollaboratorSummary getCollaborator(
            @PathVariable UUID documentId,
            @PathVariable UUID userId,
            HttpServletRequest httpRequest) {
        return collaboratorManagementService.getCollaborator(documentId, userId, httpRequest);
    }

    @PutMapping("/{userId}")
    public DocumentCollaboratorSummary updateCollaborator(
            @PathVariable UUID documentId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateCollaboratorRequest request,
            HttpServletRequest httpRequest) {
        return collaboratorManagementService.updateCollaborator(
                documentId, userId, request.permission(), httpRequest);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCollaborator(
            @PathVariable UUID documentId,
            @PathVariable UUID userId,
            HttpServletRequest httpRequest) {
        collaboratorManagementService.removeCollaborator(documentId, userId, httpRequest);
    }

    @PutMapping("/owner")
    public DocumentResponse transferOwnership(
            @PathVariable UUID documentId,
            @Valid @RequestBody TransferOwnershipRequest request,
            HttpServletRequest httpRequest) {
        return collaboratorManagementService.transferOwnership(
                documentId, request.newOwnerUserId(), httpRequest);
    }
}
