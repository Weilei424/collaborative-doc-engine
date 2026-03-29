package com.mwang.backend.config;

import com.mwang.backend.domain.User;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;

@Component
public class UserPrincipalHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        if (!(attributes.get("user") instanceof User user)) return null;
        String userId = user.getId().toString();
        return () -> userId;
    }
}
