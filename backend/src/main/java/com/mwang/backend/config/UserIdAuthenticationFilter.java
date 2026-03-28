package com.mwang.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.User;
import com.mwang.backend.service.HeaderCurrentUserProvider;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import com.mwang.backend.web.model.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class UserIdAuthenticationFilter extends OncePerRequestFilter {

    private final HeaderCurrentUserProvider userProvider;
    private final ObjectMapper objectMapper;

    public UserIdAuthenticationFilter(HeaderCurrentUserProvider userProvider, ObjectMapper objectMapper) {
        this.userProvider = userProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawUserId = request.getHeader(HeaderCurrentUserProvider.USER_ID_HEADER);
        if (rawUserId == null || rawUserId.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "USER_CONTEXT_REQUIRED", "X-User-Id header is required");
            return;
        }
        try {
            User user = userProvider.requireCurrentUser();
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(user, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
        } catch (UserNotFoundException e) {
            SecurityContextHolder.clearContext();
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "USER_NOT_FOUND", e.getMessage());
        } catch (UserContextRequiredException e) {
            SecurityContextHolder.clearContext();
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "USER_CONTEXT_REQUIRED", e.getMessage());
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiErrorResponse.of(code, message)));
    }
}
