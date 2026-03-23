package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final UserRepository userRepository;

    @Override
    public User requireCurrentUser() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            throw new UserContextRequiredException();
        }

        HttpServletRequest request = servletAttributes.getRequest();
        String rawUserId = request.getHeader(USER_ID_HEADER);
        if (rawUserId == null || rawUserId.isBlank()) {
            throw new UserContextRequiredException();
        }

        UUID userId;
        try {
            userId = UUID.fromString(rawUserId.trim());
        } catch (IllegalArgumentException ex) {
            throw new UserContextRequiredException("X-User-Id header must be a valid UUID");
        }

        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
