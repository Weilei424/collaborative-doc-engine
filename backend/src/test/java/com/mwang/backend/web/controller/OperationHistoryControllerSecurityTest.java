package com.mwang.backend.web.controller;

import com.mwang.backend.config.JwtService;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.OperationHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationHistoryController.class)
@Import(RestExceptionHandler.class)
class OperationHistoryControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean OperationHistoryService operationHistoryService;

    @Test
    void returns401WhenNoToken() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/operations", UUID.randomUUID())
                        .param("sinceVersion", "0"))
                .andExpect(status().isUnauthorized());
    }
}
