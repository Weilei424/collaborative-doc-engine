package com.mwang.backend.service;

import com.mwang.backend.web.model.AuthResponse;
import com.mwang.backend.web.model.LoginRequest;
import com.mwang.backend.web.model.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
}
