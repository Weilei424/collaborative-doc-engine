package com.mwang.backend.web.model;

import java.util.UUID;

public record UserSummaryResponse(UUID userId, String username) {}
