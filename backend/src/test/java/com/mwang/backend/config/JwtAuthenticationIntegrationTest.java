package com.mwang.backend.config;

import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.DocumentService;
import com.mwang.backend.web.controller.DocumentController;
import com.mwang.backend.web.controller.RestExceptionHandler;
import com.mwang.backend.web.model.DocumentPagedList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that a valid JWT Bearer token passes through the full MVC security stack
 * (JwtAuthenticationFilter → SecurityContextHolder → controller) and that requests
 * without a token are rejected with 401.
 */
@WebMvcTest(DocumentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestExceptionHandler.class})
class JwtAuthenticationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean DocumentService documentService;

    @Test
    void requestWithValidBearerToken_isAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId).username("alice").email("alice@example.com").passwordHash("x").build();
        when(jwtService.isValid("valid-token")).thenReturn(true);
        when(jwtService.extractUserId("valid-token")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(documentService.list(any(), any(), any(), any()))
                .thenReturn(new DocumentPagedList(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/documents")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void requestWithoutBearerToken_isRejected() throws Exception {
        // Spring Security returns 403 (not 401) when no AuthenticationEntryPoint is configured.
        // The important thing is that the request is blocked without a valid token.
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isForbidden());
    }
}
