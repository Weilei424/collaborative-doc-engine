package com.mwang.backend.service;

import com.mwang.backend.config.JwtService;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.EmailAlreadyExistsException;
import com.mwang.backend.service.exception.InvalidCredentialsException;
import com.mwang.backend.service.exception.UsernameAlreadyExistsException;
import com.mwang.backend.web.model.AuthResponse;
import com.mwang.backend.web.model.LoginRequest;
import com.mwang.backend.web.model.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @InjectMocks AuthServiceImpl authService;

    @Test
    void register_success_returnsTokenAndUserId() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret")).thenReturn("hashed");
        User saved = new User();
        saved.setId(UUID.randomUUID());
        saved.setUsername("alice");
        when(userRepository.save(any())).thenReturn(saved);
        when(jwtService.generateToken(saved)).thenReturn("jwt-token");

        AuthResponse response = authService.register(
                new RegisterRequest("alice", "alice@example.com", "secret"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(saved.getId());
        assertThat(response.username()).isEqualTo(saved.getUsername());
    }

    @Test
    void register_duplicateUsername_throwsUsernameAlreadyExistsException() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("alice", "alice@example.com", "secret")))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyExistsException() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("alice", "alice@example.com", "secret")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void login_validCredentials_returnsToken() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPasswordHash("hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.login(new LoginRequest("alice", "secret"));

        assertThat(response.token()).isEqualTo("jwt-token");
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentialsException() {
        User user = new User();
        user.setPasswordHash("hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_unknownUser_throwsInvalidCredentialsException() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "pass")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
