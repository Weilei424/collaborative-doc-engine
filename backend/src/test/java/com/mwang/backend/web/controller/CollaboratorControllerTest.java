package com.mwang.backend.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.config.TestSecurityConfig;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.service.CollaboratorManagementService;
import com.mwang.backend.service.exception.CollaboratorAlreadyExistsException;
import com.mwang.backend.service.exception.CollaboratorNotFoundException;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.web.model.AddCollaboratorRequest;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentOwnerSummary;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.TransferOwnershipRequest;
import com.mwang.backend.web.model.UpdateCollaboratorRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CollaboratorController.class)
@Import({RestExceptionHandler.class, TestSecurityConfig.class})
class CollaboratorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CollaboratorManagementService collaboratorManagementService;

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void listCollaborators_returns200WithCollaboratorList() throws Exception {
        DocumentCollaboratorSummary summary =
                new DocumentCollaboratorSummary(USER_ID, "alice", DocumentPermission.WRITE);
        when(collaboratorManagementService.listCollaborators(eq(DOCUMENT_ID), any()))
                .thenReturn(List.of(summary));

        mockMvc.perform(get("/api/documents/{documentId}/collaborators", DOCUMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].permission").value("WRITE"));
    }

    @Test
    void listCollaborators_returns403WhenAccessDenied() throws Exception {
        when(collaboratorManagementService.listCollaborators(eq(DOCUMENT_ID), any()))
                .thenThrow(new DocumentAccessDeniedException(DOCUMENT_ID, USER_ID));

        mockMvc.perform(get("/api/documents/{documentId}/collaborators", DOCUMENT_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void addCollaborator_returns201WithSummary() throws Exception {
        AddCollaboratorRequest request = new AddCollaboratorRequest(USER_ID, DocumentPermission.WRITE);
        DocumentCollaboratorSummary summary =
                new DocumentCollaboratorSummary(USER_ID, "alice", DocumentPermission.WRITE);
        when(collaboratorManagementService.addCollaborator(eq(DOCUMENT_ID), eq(USER_ID), eq(DocumentPermission.WRITE), any()))
                .thenReturn(summary);

        mockMvc.perform(post("/api/documents/{documentId}/collaborators", DOCUMENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.permission").value("WRITE"));
    }

    @Test
    void addCollaborator_returns403WhenNotAdmin() throws Exception {
        AddCollaboratorRequest request = new AddCollaboratorRequest(USER_ID, DocumentPermission.READ);
        when(collaboratorManagementService.addCollaborator(eq(DOCUMENT_ID), eq(USER_ID), any(), any()))
                .thenThrow(new DocumentAccessDeniedException(DOCUMENT_ID, USER_ID));

        mockMvc.perform(post("/api/documents/{documentId}/collaborators", DOCUMENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void addCollaborator_returns409WhenAlreadyExists() throws Exception {
        AddCollaboratorRequest request = new AddCollaboratorRequest(USER_ID, DocumentPermission.READ);
        when(collaboratorManagementService.addCollaborator(eq(DOCUMENT_ID), eq(USER_ID), any(), any()))
                .thenThrow(new CollaboratorAlreadyExistsException(DOCUMENT_ID, USER_ID));

        mockMvc.perform(post("/api/documents/{documentId}/collaborators", DOCUMENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLABORATOR_ALREADY_EXISTS"));
    }

    @Test
    void addCollaborator_returns400WhenPermissionMissing() throws Exception {
        mockMvc.perform(post("/api/documents/{documentId}/collaborators", DOCUMENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + USER_ID + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCollaborator_returns200WithUpdatedSummary() throws Exception {
        UpdateCollaboratorRequest request = new UpdateCollaboratorRequest(DocumentPermission.ADMIN);
        DocumentCollaboratorSummary summary =
                new DocumentCollaboratorSummary(USER_ID, "alice", DocumentPermission.ADMIN);
        when(collaboratorManagementService.updateCollaborator(eq(DOCUMENT_ID), eq(USER_ID), eq(DocumentPermission.ADMIN), any()))
                .thenReturn(summary);

        mockMvc.perform(put("/api/documents/{documentId}/collaborators/{userId}", DOCUMENT_ID, USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("ADMIN"));
    }

    @Test
    void updateCollaborator_returns404WhenNotFound() throws Exception {
        UpdateCollaboratorRequest request = new UpdateCollaboratorRequest(DocumentPermission.WRITE);
        when(collaboratorManagementService.updateCollaborator(eq(DOCUMENT_ID), eq(USER_ID), eq(DocumentPermission.WRITE), any()))
                .thenThrow(new CollaboratorNotFoundException(DOCUMENT_ID, USER_ID));

        mockMvc.perform(put("/api/documents/{documentId}/collaborators/{userId}", DOCUMENT_ID, USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLABORATOR_NOT_FOUND"));
    }

    @Test
    void removeCollaborator_returns204() throws Exception {
        mockMvc.perform(delete("/api/documents/{documentId}/collaborators/{userId}", DOCUMENT_ID, USER_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeCollaborator_returns404WhenNotFound() throws Exception {
        doThrow(new CollaboratorNotFoundException(DOCUMENT_ID, USER_ID))
                .when(collaboratorManagementService).removeCollaborator(eq(DOCUMENT_ID), eq(USER_ID), any());

        mockMvc.perform(delete("/api/documents/{documentId}/collaborators/{userId}", DOCUMENT_ID, USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeCollaborator_returns400WhenOwnerRemovalAttempted() throws Exception {
        doThrow(new InvalidCollaborationRequestException("Cannot remove the document owner"))
                .when(collaboratorManagementService).removeCollaborator(eq(DOCUMENT_ID), eq(USER_ID), any());

        mockMvc.perform(delete("/api/documents/{documentId}/collaborators/{userId}", DOCUMENT_ID, USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COLLABORATION_REQUEST"));
    }

    @Test
    void transferOwnership_returns200WithUpdatedDocument() throws Exception {
        UUID newOwnerId = UUID.randomUUID();
        TransferOwnershipRequest request = new TransferOwnershipRequest(newOwnerId);
        DocumentResponse response = new DocumentResponse(
                DOCUMENT_ID, "Doc", null, DocumentVisibility.PRIVATE, 0L, null, null,
                new DocumentOwnerSummary(newOwnerId, "newowner"), List.of(), "OWNER");
        when(collaboratorManagementService.transferOwnership(eq(DOCUMENT_ID), eq(newOwnerId), any()))
                .thenReturn(response);

        mockMvc.perform(put("/api/documents/{documentId}/collaborators/owner", DOCUMENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.owner.username").value("newowner"));
    }

    @Test
    void transferOwnership_returns403WhenNotOwner() throws Exception {
        UUID newOwnerId = UUID.randomUUID();
        TransferOwnershipRequest request = new TransferOwnershipRequest(newOwnerId);
        when(collaboratorManagementService.transferOwnership(eq(DOCUMENT_ID), eq(newOwnerId), any()))
                .thenThrow(new DocumentAccessDeniedException(DOCUMENT_ID, USER_ID));

        mockMvc.perform(put("/api/documents/{documentId}/collaborators/owner", DOCUMENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void transferOwnership_returns404WhenTargetNotACollaborator() throws Exception {
        UUID newOwnerId = UUID.randomUUID();
        TransferOwnershipRequest request = new TransferOwnershipRequest(newOwnerId);
        when(collaboratorManagementService.transferOwnership(eq(DOCUMENT_ID), eq(newOwnerId), any()))
                .thenThrow(new CollaboratorNotFoundException(DOCUMENT_ID, newOwnerId));

        mockMvc.perform(put("/api/documents/{documentId}/collaborators/owner", DOCUMENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
