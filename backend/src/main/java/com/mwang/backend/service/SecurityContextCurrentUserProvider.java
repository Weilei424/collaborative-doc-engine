package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.service.exception.UserContextRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Primary
@Service
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public User requireCurrentUser(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            throw new UserContextRequiredException();
        }
        return user;
    }

    @Override
    public User requireCurrentUser(SimpMessageHeaderAccessor headerAccessor) {
        if (headerAccessor == null) throw new UserContextRequiredException();
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs == null) throw new UserContextRequiredException();
        Object user = attrs.get("user");
        if (!(user instanceof User u)) throw new UserContextRequiredException();
        return u;
    }
}
