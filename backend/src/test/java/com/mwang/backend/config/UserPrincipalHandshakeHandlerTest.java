package com.mwang.backend.config;

import com.mwang.backend.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.http.server.ServletServerHttpRequest;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserPrincipalHandshakeHandlerTest {

    private final UserPrincipalHandshakeHandler handler = new UserPrincipalHandshakeHandler();

    @Test
    void withUserInAttributes_returnsPrincipalWithUserId() {
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        Map<String, Object> attributes = Map.of("user", user);

        Principal principal = handler.determineUser(request, mock(WebSocketHandler.class), attributes);

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo(userId.toString());
    }

    @Test
    void withNoUserInAttributes_returnsNull() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        Map<String, Object> attributes = new HashMap<>();

        Principal principal = handler.determineUser(request, mock(WebSocketHandler.class), attributes);

        assertThat(principal).isNull();
    }
}
