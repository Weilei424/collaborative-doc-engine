package com.mwang.backend.service;

import com.mwang.backend.domain.User;

public interface CurrentUserProvider {
    User requireCurrentUser();
}
