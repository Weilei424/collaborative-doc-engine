package com.mwang.backend.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.service.DocumentService;
import com.mwang.backend.web.model.DocumentDto;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DocumentService documentService;

    @Test
    void testCreate() throws Exception {
        DocumentDto dto = new DocumentDto();
        dto.setId(UUID.randomUUID());
        dto.setTitle("Test Document");
        dto.setContent("Test content");

        Mockito.when(documentService.create(any(DocumentDto.class))).thenReturn(dto);

        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dto.getId().toString()))
                .andExpect(jsonPath("$.title").value("Test Document"))
                .andExpect(jsonPath("$.content").value("Test content"));
    }

    @Test
    void testGetAll() throws Exception {
        DocumentDto dto = new DocumentDto();
        dto.setId(UUID.randomUUID());
        dto.setTitle("Doc1");
        dto.setContent("Content1");

        Mockito.when(documentService.getAll()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(dto.getId().toString()))
                .andExpect(jsonPath("$[0].title").value("Doc1"))
                .andExpect(jsonPath("$[0].content").value("Content1"));
    }

    @Test
    void testGetById() throws Exception {
        UUID id = UUID.randomUUID();
        DocumentDto dto = new DocumentDto();
        dto.setId(id);
        dto.setTitle("DocById");
        dto.setContent("ContentById");

        Mockito.when(documentService.getById(id)).thenReturn(Optional.of(dto));

        mockMvc.perform(get("/api/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("DocById"))
                .andExpect(jsonPath("$.content").value("ContentById"));
    }

    @Test
    void testUpdate() throws Exception {
        UUID id = UUID.randomUUID();
        DocumentDto dto = new DocumentDto();
        dto.setId(id);
        dto.setTitle("Updated Title");
        dto.setContent("Updated Content");

        Mockito.when(documentService.update(eq(id), any(DocumentDto.class))).thenReturn(Optional.of(dto));

        mockMvc.perform(put("/api/documents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.content").value("Updated Content"));
    }

    @Test
    void testDelete() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.doNothing().when(documentService).delete(id);

        mockMvc.perform(delete("/api/documents/{id}", id))
                .andExpect(status().isOk());
    }
}
