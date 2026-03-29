package com.mwang.backend.config;

import com.mwang.backend.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setup() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret",
                "dGhpcy1pcy1hLTI1Ni1iaXQtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQ=");
        ReflectionTestUtils.setField(jwtService, "expirationMs", 86400000L);
    }

    @Test
    void generateToken_extractUserId_roundtrip() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractUserId(token)).isEqualTo(user.getId());
    }

    @Test
    void isValid_withValidToken_returnsTrue() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        assertThat(jwtService.isValid(jwtService.generateToken(user))).isTrue();
    }

    @Test
    void isValid_withTamperedToken_returnsFalse() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        String tampered = jwtService.generateToken(user) + "x";
        assertThat(jwtService.isValid(tampered)).isFalse();
    }

    @Test
    void isValid_withBlankToken_returnsFalse() {
        assertThat(jwtService.isValid("")).isFalse();
    }
}
