package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpAttributes;
import org.springframework.messaging.simp.SimpAttributesContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    public static final String USER_ID_HEADER = "X-User-Id";

    private final UserRepository userRepository;

    @Override
    public User requireCurrentUser() {
        String rawUserId = trimToNull(extractUserIdFromMessagingSession());
        if (rawUserId != null) {
            return requireUser(rawUserId);
        }

        rawUserId = trimToNull(extractUserIdFromHttpRequest());
        if (rawUserId != null) {
            return requireUser(rawUserId);
        }

        throw new UserContextRequiredException("X-User-Id header or STOMP session context is required");
    }

    public User requireCurrentUserFromSessionAttributes(Map<String, Object> sessionAttributes) {
        String rawUserId = trimToNull(extractUserIdFromSessionAttributes(sessionAttributes));
        if (rawUserId == null) {
            throw new UserContextRequiredException("X-User-Id STOMP session context is required");
        }

        return requireUser(rawUserId);
    }

    private User requireUser(String rawUserId) {
        UUID userId;
        try {
            userId = UUID.fromString(rawUserId);
        } catch (IllegalArgumentException ex) {
            throw new UserContextRequiredException("X-User-Id context must be a valid UUID");
        }

        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private String extractUserIdFromHttpRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }

        HttpServletRequest request = servletAttributes.getRequest();
        return request.getHeader(USER_ID_HEADER);
    }

    private String extractUserIdFromMessagingSession() {
        SimpAttributes attributes = SimpAttributesContextHolder.getAttributes();
        if (attributes == null) {
            return null;
        }

        Object rawUserId = attributes.getAttribute(USER_ID_HEADER);
        return rawUserId == null ? null : rawUserId.toString();
    }

    private String extractUserIdFromSessionAttributes(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            return null;
        }

        Object rawUserId = sessionAttributes.get(USER_ID_HEADER);
        return rawUserId == null ? null : rawUserId.toString();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
