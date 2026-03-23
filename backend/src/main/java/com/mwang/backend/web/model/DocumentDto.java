package com.mwang.backend.web.model;

import java.util.UUID;

@Deprecated(forRemoval = true)
public record DocumentDto(UUID id, String title, String content) {
}
