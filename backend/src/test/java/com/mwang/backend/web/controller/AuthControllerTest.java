package com.mwang.backend.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.config.TestSecurityConfig;
import com.mwang.backend.service.AuthService;
import com.mwang.backend.service.exception.EmailAlreadyExistsException;
import com.mwang.backend.service.exception.InvalidCredentialsException;
import com.mwang.backend.service.exception.UsernameAlreadyExistsException;
import com.mwang.backend.web.controller.RestExceptionHandler;
import com.mwang.backend.web.model.AuthResponse;
import com.mwang.backend.web.model.LoginRequest;
import com.mwang.backend.web.model.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({RestExceptionHandler.class, TestSecurityConfig.class})
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AuthService authService;

    @Test
    void register_validRequest_returns201WithToken() throws Exception {
        AuthResponse response = new AuthResponse("jwt-token", UUID.randomUUID(), "alice");
        when(authService.register(any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("alice", "alice@example.com", "password123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        when(authService.register(any())).thenThrow(new UsernameAlreadyExistsException("alice"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("alice", "alice@example.com", "password123"))))
                .andExpect(status().isConflict());
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        when(authService.register(any())).thenThrow(new EmailAlreadyExistsException("alice@example.com"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("alice", "alice@example.com", "password123"))))
                .andExpect(status().isConflict());
    }

    @Test
    void register_invalidRequest_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"bad\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        AuthResponse response = new AuthResponse("jwt-token", UUID.randomUUID(), "alice");
        when(authService.login(any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("alice", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("alice", "wrong"))))
                .andExpect(status().isUnauthorized());
    }
}
