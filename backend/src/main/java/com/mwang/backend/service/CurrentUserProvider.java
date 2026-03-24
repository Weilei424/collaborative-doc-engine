package com.mwang.backend.service;

import com.mwang.backend.domain.User;

import java.util.Map;

public interface CurrentUserProvider {
    User requireCurrentUser();

    default User requireCurrentUser(Map<String, Object> sessionAttributes) {
        return requireCurrentUser();
    }
}