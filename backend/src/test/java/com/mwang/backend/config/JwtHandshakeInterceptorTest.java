package com.mwang.backend.config;

import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtHandshakeInterceptorTest {

    @Mock JwtService jwtService;
    @Mock UserRepository userRepository;
    @InjectMocks JwtHandshakeInterceptor interceptor;

    @Mock WebSocketHandler wsHandler;

    private final UUID userId = UUID.randomUUID();

    @Test
    void validToken_storesUserInAttributes() throws Exception {
        User user = new User();
        user.setId(userId);

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setParameter("token", "valid-token");
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);

        when(jwtService.isValid("valid-token")).thenReturn(true);
        when(jwtService.extractUserId("valid-token")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, null, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes.get("user")).isEqualTo(user);
    }

    @Test
    void missingToken_doesNotStoreUser() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, null, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).doesNotContainKey("user");
    }

    @Test
    void invalidToken_doesNotStoreUser() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setParameter("token", "bad-token");
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);

        when(jwtService.isValid("bad-token")).thenReturn(false);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, null, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).doesNotContainKey("user");
    }
}
