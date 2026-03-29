package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * @deprecated Replaced by {@link SecurityContextCurrentUserProvider}. Retained for backward compatibility.
 */
@Deprecated
@Component
@RequiredArgsConstructor
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    public static final String USER_ID_HEADER = "X-User-Id";

    private final UserRepository userRepository;

    @Override
    public User requireCurrentUser(HttpServletRequest request) {
        String rawUserId = trimToNull(request.getHeader(USER_ID_HEADER));
        if (rawUserId == null) {
            throw new UserContextRequiredException("X-User-Id header is required");
        }
        return requireUser(rawUserId);
    }

    @Override
    public User requireCurrentUser(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs == null) {
            throw new UserContextRequiredException("X-User-Id STOMP session context is required");
        }
        Object rawUserId = attrs.get(USER_ID_HEADER);
        if (rawUserId == null) {
            throw new UserContextRequiredException("X-User-Id STOMP session context is required");
        }
        return requireUser(trimToNull(rawUserId.toString()));
    }

    private User requireUser(String rawUserId) {
        if (rawUserId == null) {
            throw new UserContextRequiredException("X-User-Id context must be a valid UUID");
        }
        UUID userId;
        try {
            userId = UUID.fromString(rawUserId);
        } catch (IllegalArgumentException ex) {
            throw new UserContextRequiredException("X-User-Id context must be a valid UUID");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
