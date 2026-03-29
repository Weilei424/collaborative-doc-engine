package com.mwang.backend.web.model;

import java.util.UUID;

public record AuthResponse(String token, UUID userId, String username) {}
