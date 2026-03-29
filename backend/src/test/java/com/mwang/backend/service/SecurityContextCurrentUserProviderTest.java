package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.service.exception.UserContextRequiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityContextCurrentUserProviderTest {

    private final SecurityContextCurrentUserProvider provider =
            new SecurityContextCurrentUserProvider();

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void requireCurrentUser_withAuthenticatedUser_returnsUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        User result = provider.requireCurrentUser(new MockHttpServletRequest());

        assertThat(result).isEqualTo(user);
    }

    @Test
    void requireCurrentUser_withNoAuthentication_throwsUserContextRequiredException() {
        assertThatThrownBy(() -> provider.requireCurrentUser(new MockHttpServletRequest()))
                .isInstanceOf(UserContextRequiredException.class);
    }

    @Test
    void requireCurrentUser_stomp_withUserInSession_returnsUser() {
        User user = new User();
        user.setId(UUID.randomUUID());

        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(Map.of("user", user));

        User result = provider.requireCurrentUser(accessor);

        assertThat(result).isEqualTo(user);
    }

    @Test
    void requireCurrentUser_stomp_withNullAccessor_throwsUserContextRequiredException() {
        assertThatThrownBy(() -> provider.requireCurrentUser((SimpMessageHeaderAccessor) null))
                .isInstanceOf(UserContextRequiredException.class);
    }

    @Test
    void requireCurrentUser_stomp_withNoUserInSession_throwsUserContextRequiredException() {
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(new java.util.HashMap<>());

        assertThatThrownBy(() -> provider.requireCurrentUser(accessor))
                .isInstanceOf(UserContextRequiredException.class);
    }
}
