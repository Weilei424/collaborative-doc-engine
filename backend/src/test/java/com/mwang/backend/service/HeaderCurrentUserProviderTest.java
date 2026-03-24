package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpAttributes;
import org.springframework.messaging.simp.SimpAttributesContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeaderCurrentUserProviderTest {

    @Mock
    private UserRepository userRepository;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        SimpAttributesContextHolder.resetAttributes();
    }

    @Test
    void requireCurrentUserRejectsMissingHeader() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThatThrownBy(provider::requireCurrentUser)
                .isInstanceOf(UserContextRequiredException.class);
    }

    @Test
    void requireCurrentUserRejectsInvalidUuidHeader() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "not-a-uuid");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThatThrownBy(provider::requireCurrentUser)
                .isInstanceOf(UserContextRequiredException.class)
                .hasMessageContaining("valid UUID");
    }

    @Test
    void requireCurrentUserRejectsUnknownUser() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(provider::requireCurrentUser)
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void requireCurrentUserReturnsResolvedUser() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("actor").email("actor@example.com").passwordHash("hash").build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        User resolved = provider.requireCurrentUser();

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void requireCurrentUserReturnsResolvedUserFromMessagingSession() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("actor").email("actor@example.com").passwordHash("hash").build();
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("X-User-Id", userId.toString());
        SimpAttributesContextHolder.setAttributes(new SimpAttributes("stomp-session", sessionAttributes));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        User resolved = provider.requireCurrentUser();

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void requireCurrentUserPrefersMessagingSessionOverHttpHeader() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID messagingUserId = UUID.randomUUID();
        UUID httpUserId = UUID.randomUUID();
        User messagingUser = User.builder().id(messagingUserId).username("socket-user").email("socket@example.com").passwordHash("hash").build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", httpUserId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("X-User-Id", messagingUserId.toString());
        SimpAttributesContextHolder.setAttributes(new SimpAttributes("stomp-session", sessionAttributes));
        when(userRepository.findById(messagingUserId)).thenReturn(Optional.of(messagingUser));

        User resolved = provider.requireCurrentUser();

        assertThat(resolved).isEqualTo(messagingUser);
    }

    @Test
    void requireCurrentUserFromSessionAttributesRejectsMissingUserContext() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);

        assertThatThrownBy(() -> provider.requireCurrentUserFromSessionAttributes(new HashMap<>()))
                .isInstanceOf(UserContextRequiredException.class)
                .hasMessageContaining("STOMP session context");
    }

    @Test
    void requireCurrentUserFromSessionAttributesReturnsResolvedUser() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("actor").email("actor@example.com").passwordHash("hash").build();
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(HeaderCurrentUserProvider.USER_ID_HEADER, " " + userId + " ");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        User resolved = provider.requireCurrentUserFromSessionAttributes(sessionAttributes);

        assertThat(resolved).isEqualTo(user);
    }
}
