package com.mwang.backend.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.service.DocumentService;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import com.mwang.backend.web.model.CreateDocumentRequest;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentOwnerSummary;
import com.mwang.backend.web.model.DocumentPagedList;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.UpdateDocumentRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import(RestExceptionHandler.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentService documentService;

    @Test
    void createReturns201AndRichDocumentResponse() throws Exception {
        UUID ownerId = UUID.randomUUID();
        DocumentResponse response = sampleResponse(ownerId, "owner-user", DocumentPermission.ADMIN);

        Mockito.when(documentService.create(any(CreateDocumentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/documents")
                        .header("X-User-Id", ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDocumentRequest("Doc", "Hello", DocumentVisibility.PRIVATE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.owner.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.owner.username").value("owner-user"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.currentVersion").value(0))
                .andExpect(jsonPath("$.currentUserPermission").value("OWNER"));
    }

    @Test
    void listReturnsUnifiedPagedResponse() throws Exception {
        UUID actorId = UUID.randomUUID();
        DocumentResponse response = sampleResponse(actorId, "owner-user", DocumentPermission.ADMIN);
        DocumentPagedList pagedList = new DocumentPagedList(List.of(response), 0, 20, 1L, 1);

        Mockito.when(documentService.list(eq(DocumentListScope.ACCESSIBLE), eq("doc"), any(Pageable.class)))
                .thenReturn(pagedList);

        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", actorId)
                        .param("scope", "accessible")
                        .param("query", "doc")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(response.id().toString()))
                .andExpect(jsonPath("$.items[0].currentUserPermission").value("OWNER"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listAcceptsOwnedScope() throws Exception {
        Mockito.when(documentService.list(eq(DocumentListScope.OWNED), eq(null), any(Pageable.class)))
                .thenReturn(new DocumentPagedList(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("scope", "owned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void listAcceptsSharedScope() throws Exception {
        Mockito.when(documentService.list(eq(DocumentListScope.SHARED), eq(null), any(Pageable.class)))
                .thenReturn(new DocumentPagedList(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("scope", "shared"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void listAcceptsPublicScope() throws Exception {
        Mockito.when(documentService.list(eq(DocumentListScope.PUBLIC), eq(null), any(Pageable.class)))
                .thenReturn(new DocumentPagedList(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void getByIdReturnsRichResponseForAllowedActor() throws Exception {
        UUID actorId = UUID.randomUUID();
        DocumentResponse response = sampleResponse(actorId, "owner-user", DocumentPermission.ADMIN);

        Mockito.when(documentService.getById(response.id())).thenReturn(response);

        mockMvc.perform(get("/api/documents/{id}", response.id())
                        .header("X-User-Id", actorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborators[0].permission").value("ADMIN"))
                .andExpect(jsonPath("$.createdAt").value("2026-03-22T12:00:00Z"));
    }

    @Test
    void missingActorHeaderReturnsStableErrorEnvelope() throws Exception {
        Mockito.when(documentService.list(eq(DocumentListScope.OWNED), eq(null), any(Pageable.class)))
                .thenThrow(new UserContextRequiredException());

        mockMvc.perform(get("/api/documents")
                        .param("scope", "owned"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void invalidScopeReturnsStableErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("scope", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
    }

    @Test
    void invalidPageReturnsStableValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void nonNumericPageReturnsStableValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidDocumentIdReturnsStableValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/documents/{id}", "not-a-uuid")
                        .header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidVisibilityReturnsStableValidationEnvelope() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/documents/{id}", id)
                        .header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"title\":\"Doc\",\"content\":\"Updated\",\"visibility\":\"BROKEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidSortDirectionReturnsStableValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("sort", "updatedAt,sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void blankSortPropertyReturnsStableValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("sort", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateReturnsValidationErrorForBlankTitle() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/documents/{id}", id)
                        .header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateDocumentRequest(" ", "Updated", DocumentVisibility.SHARED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void disallowedReadReturns403() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Mockito.when(documentService.getById(documentId))
                .thenThrow(new DocumentAccessDeniedException(documentId, actorId));

        mockMvc.perform(get("/api/documents/{id}", documentId)
                        .header("X-User-Id", actorId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void missingDocumentReturns404() throws Exception {
        UUID documentId = UUID.randomUUID();

        Mockito.when(documentService.getById(documentId))
                .thenThrow(new DocumentNotFoundException(documentId));

        mockMvc.perform(get("/api/documents/{id}", documentId)
                        .header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void unknownUserReturnsStableErrorEnvelope() throws Exception {
        UUID userId = UUID.randomUUID();

        Mockito.when(documentService.list(eq(DocumentListScope.ACCESSIBLE), eq(null), any(Pageable.class)))
                .thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(get("/api/documents")
                        .header("X-User-Id", userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void updateForbiddenReturns403() throws Exception {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Mockito.when(documentService.update(eq(id), any(UpdateDocumentRequest.class)))
                .thenThrow(new DocumentAccessDeniedException(id, actorId));

        mockMvc.perform(put("/api/documents/{id}", id)
                        .header("X-User-Id", actorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateDocumentRequest("Doc", "Updated", DocumentVisibility.PRIVATE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void deleteForbiddenReturns403() throws Exception {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Mockito.doThrow(new DocumentAccessDeniedException(id, actorId))
                .when(documentService).delete(id);

        mockMvc.perform(delete("/api/documents/{id}", id)
                        .header("X-User-Id", actorId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.doNothing().when(documentService).delete(id);

        mockMvc.perform(delete("/api/documents/{id}", id)
                        .header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }

    private DocumentResponse sampleResponse(UUID ownerId, String ownerUsername, DocumentPermission collaboratorPermission) {
        return new DocumentResponse(
                UUID.randomUUID(),
                "Doc",
                "Hello",
                DocumentVisibility.PRIVATE,
                0L,
                Instant.parse("2026-03-22T12:00:00Z"),
                Instant.parse("2026-03-22T12:30:00Z"),
                new DocumentOwnerSummary(ownerId, ownerUsername),
                List.of(new DocumentCollaboratorSummary(UUID.randomUUID(), "collab-user", collaboratorPermission)),
                "OWNER"
        );
    }
}





