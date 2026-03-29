package com.mwang.backend.web.controller;

import com.mwang.backend.config.TestSecurityConfig;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import(TestSecurityConfig.class)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserRepository userRepository;

    @Test
    void search_withMatchingPrefix_returnsUsers() throws Exception {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        when(userRepository.searchByUsernameOrEmail(eq("ali"), any())).thenReturn(List.of(user));

        mockMvc.perform(get("/api/users/search").param("q", "ali"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"));
    }

    @Test
    void search_withShortQuery_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/users/search").param("q", "a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void search_withBlankQuery_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/users/search").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void search_withMissingQuery_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/users/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
