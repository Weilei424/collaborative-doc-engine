package com.mwang.backend.service;

import com.mwang.backend.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

public interface CurrentUserProvider {
    User requireCurrentUser(HttpServletRequest request);
    User requireCurrentUser(SimpMessageHeaderAccessor headerAccessor);
}
