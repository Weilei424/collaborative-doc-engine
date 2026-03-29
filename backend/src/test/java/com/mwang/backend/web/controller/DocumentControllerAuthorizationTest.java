package com.mwang.backend.web.controller;

import com.mwang.backend.config.TestSecurityConfig;
import com.mwang.backend.service.DocumentService;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

@WebMvcTest(DocumentController.class)
@Import({RestExceptionHandler.class, TestSecurityConfig.class})
@ActiveProfiles("test")
class DocumentControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @Test
    void userContextRequired_returns400() throws Exception {
        when(documentService.create(any(), any()))
                .thenThrow(new UserContextRequiredException("X-User-Id header is required"));

        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void userNotFound_returns400() throws Exception {
        UUID userId = UUID.randomUUID();
        when(documentService.create(any(), any()))
                .thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(post("/api/documents")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void documentAccessDenied_returns403() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(documentService.getById(any(), any()))
                .thenThrow(new DocumentAccessDeniedException(docId, userId));

        mockMvc.perform(get("/api/documents/{id}", docId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
