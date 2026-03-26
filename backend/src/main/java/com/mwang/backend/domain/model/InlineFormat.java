package com.mwang.backend.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InlineFormat {
    private int offset;
    private int length;
    private Map<String, Object> attributes;
}
