package com.mwang.backend.web.model;

import com.mwang.backend.domain.DocumentVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDocumentRequest(
        @NotBlank(message = "Title must not be blank")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @Size(max = 1_000_000, message = "Content must not exceed 1 MB")
        String content,

        DocumentVisibility visibility) {
}
