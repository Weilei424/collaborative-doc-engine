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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
}
