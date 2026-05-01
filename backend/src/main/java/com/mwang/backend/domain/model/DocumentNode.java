package com.mwang.backend.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentNode {
    private String type;
    private String text;
    @Builder.Default
    private List<InlineFormat> formats = new ArrayList<>();
    private List<DocumentNode> children;

    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }

    public DocumentNode copy() {
        List<InlineFormat> formatsCopy = formats == null ? new ArrayList<>() :
                formats.stream()
                        .map(f -> new InlineFormat(f.getOffset(), f.getLength(),
                                new LinkedHashMap<>(f.getAttributes())))
                        .collect(Collectors.toCollection(ArrayList::new));
        List<DocumentNode> childrenCopy = children == null ? null :
                children.stream()
                        .map(DocumentNode::copy)
                        .collect(Collectors.toCollection(ArrayList::new));
        return DocumentNode.builder()
                .type(type)
                .text(text)
                .formats(formatsCopy)
                .children(childrenCopy)
                .build();
    }
}
