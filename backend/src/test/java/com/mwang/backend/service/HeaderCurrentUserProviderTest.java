package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeaderCurrentUserProviderTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void requireCurrentUserRejectsMissingHeader() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> provider.requireCurrentUser(request))
                .isInstanceOf(UserContextRequiredException.class);
    }

    @Test
    void requireCurrentUserRejectsInvalidUuidHeader() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "not-a-uuid");

        assertThatThrownBy(() -> provider.requireCurrentUser(request))
                .isInstanceOf(UserContextRequiredException.class)
                .hasMessageContaining("valid UUID");
    }

    @Test
    void requireCurrentUserRejectsUnknownUser() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.requireCurrentUser(request))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void requireCurrentUserReturnsResolvedUser() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("actor").email("actor@example.com").passwordHash("hash").build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        User resolved = provider.requireCurrentUser(request);

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void requireCurrentUser_stomp_rejectsMissingSessionAttributes() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(null);

        assertThatThrownBy(() -> provider.requireCurrentUser(accessor))
                .isInstanceOf(UserContextRequiredException.class)
                .hasMessageContaining("STOMP session context");
    }

    @Test
    void requireCurrentUser_stomp_rejectsMissingUserIdInAttributes() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(new HashMap<>());

        assertThatThrownBy(() -> provider.requireCurrentUser(accessor))
                .isInstanceOf(UserContextRequiredException.class)
                .hasMessageContaining("STOMP session context");
    }

    @Test
    void requireCurrentUser_stomp_returnsResolvedUser() {
        HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(userRepository);
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("actor").email("actor@example.com").passwordHash("hash").build();
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(HeaderCurrentUserProvider.USER_ID_HEADER, " " + userId + " ");
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(sessionAttributes);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        User resolved = provider.requireCurrentUser(accessor);

        assertThat(resolved).isEqualTo(user);
    }
}
