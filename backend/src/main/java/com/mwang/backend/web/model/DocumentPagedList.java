package com.mwang.backend.web.model;

import java.util.List;

public record DocumentPagedList(
        List<DocumentResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
