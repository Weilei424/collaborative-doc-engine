package com.mwang.backend.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwang.backend.domain.DocumentOperationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentTree {

    @Builder.Default
    private List<DocumentNode> children = new ArrayList<>();

    public DocumentTree copy() {
        List<DocumentNode> childrenCopy = children == null ? new ArrayList<>() :
                children.stream()
                        .map(DocumentNode::copy)
                        .collect(Collectors.toCollection(ArrayList::new));
        return DocumentTree.builder()
                .children(childrenCopy)
                .build();
    }

    public DocumentNode nodeAt(List<Integer> path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty");
        }
        DocumentNode node = children.get(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            node = node.getChildren().get(path.get(i));
        }
        return node;
    }

    public List<DocumentNode> siblingsOf(List<Integer> path) {
        if (path.size() == 1) {
            return children;
        }
        return nodeAt(path.subList(0, path.size() - 1)).getChildren();
    }

    public JsonNode applyOperation(DocumentOperationType type, JsonNode payload) {
        return switch (type) {
            case INSERT_TEXT -> applyInsertText(payload);
            case DELETE_RANGE -> applyDeleteRange(payload);
            case FORMAT_RANGE -> applyFormatRange(payload);
            case SPLIT_BLOCK -> applySplitBlock(payload);
            case MERGE_BLOCK -> applyMergeBlock(payload);
            case SET_BLOCK_TYPE -> applySetBlockType(payload);
            case NO_OP -> payload;
        };
    }

    private List<Integer> path(JsonNode payload) {
        List<Integer> result = new ArrayList<>();
        payload.get("path").forEach(n -> result.add(n.asInt()));
        return result;
    }

    private JsonNode applyInsertText(JsonNode payload) {
        List<Integer> path = path(payload);
        int offset = payload.get("offset").asInt();
        String text = payload.get("text").asText();
        DocumentNode node = nodeAt(path);
        String current = node.getText() != null ? node.getText() : "";
        node.setText(current.substring(0, offset) + text + current.substring(offset));
        for (InlineFormat fmt : node.getFormats()) {
            if (fmt.getOffset() >= offset) {
                fmt.setOffset(fmt.getOffset() + text.length());
            } else if (fmt.getOffset() + fmt.getLength() > offset) {
                fmt.setLength(fmt.getLength() + text.length());
            }
        }
        return payload;
    }

    private JsonNode applyDeleteRange(JsonNode payload) {
        List<Integer> path = path(payload);
        int offset = payload.get("offset").asInt();
        int length = payload.get("length").asInt();
        DocumentNode node = nodeAt(path);
        String current = node.getText() != null ? node.getText() : "";
        node.setText(current.substring(0, offset) + current.substring(offset + length));
        int end = offset + length;
        node.getFormats().removeIf(f -> f.getOffset() >= offset && f.getOffset() + f.getLength() <= end);
        for (InlineFormat fmt : node.getFormats()) {
            if (fmt.getOffset() >= end) {
                fmt.setOffset(fmt.getOffset() - length);
            } else if (fmt.getOffset() >= offset) {
                fmt.setLength(offset - fmt.getOffset());
            } else if (fmt.getOffset() + fmt.getLength() > offset) {
                int overlap = Math.min(fmt.getOffset() + fmt.getLength(), end) - offset;
                fmt.setLength(fmt.getLength() - overlap);
            }
        }
        return payload;
    }

    private JsonNode applyFormatRange(JsonNode payload) {
        List<Integer> path = path(payload);
        int offset = payload.get("offset").asInt();
        int length = payload.get("length").asInt();
        JsonNode attrs = payload.get("attributes");
        DocumentNode node = nodeAt(path);
        java.util.Map<String, Object> attrMap = new java.util.LinkedHashMap<>();
        attrs.fields().forEachRemaining(e -> attrMap.put(e.getKey(), e.getValue().isBoolean() ? e.getValue().asBoolean() : e.getValue().asText()));
        node.getFormats().add(new InlineFormat(offset, length, attrMap));
        return payload;
    }

    private JsonNode applySplitBlock(JsonNode payload) {
        List<Integer> path = path(payload);
        int offset = payload.get("offset").asInt();
        DocumentNode node = nodeAt(path);
        String text = node.getText() != null ? node.getText() : "";
        String firstText = text.substring(0, offset);
        String secondText = text.substring(offset);

        List<InlineFormat> firstFormats = new ArrayList<>();
        List<InlineFormat> secondFormats = new ArrayList<>();
        for (InlineFormat fmt : node.getFormats()) {
            int fEnd = fmt.getOffset() + fmt.getLength();
            if (fEnd <= offset) {
                firstFormats.add(fmt);
            } else if (fmt.getOffset() >= offset) {
                secondFormats.add(new InlineFormat(fmt.getOffset() - offset, fmt.getLength(), fmt.getAttributes()));
            } else {
                // spans the split — clip to first half, carry remainder into second half
                firstFormats.add(new InlineFormat(fmt.getOffset(), offset - fmt.getOffset(), fmt.getAttributes()));
                secondFormats.add(new InlineFormat(0, fEnd - offset, fmt.getAttributes()));
            }
        }

        DocumentNode second = DocumentNode.builder()
                .type(node.getType())
                .text(secondText)
                .formats(secondFormats)
                .build();
        node.setText(firstText);
        node.setFormats(firstFormats);

        List<DocumentNode> siblings = siblingsOf(path);
        int idx = path.get(path.size() - 1);
        siblings.add(idx + 1, second);
        return payload;
    }

    private JsonNode applyMergeBlock(JsonNode payload) {
        List<Integer> path = path(payload);
        List<DocumentNode> siblings = siblingsOf(path);
        int idx = path.get(path.size() - 1);
        DocumentNode primary = siblings.get(idx);
        DocumentNode secondary = siblings.get(idx + 1);
        int primaryTextLength = primary.getText() != null ? primary.getText().length() : 0;

        String mergedText = (primary.getText() != null ? primary.getText() : "")
                + (secondary.getText() != null ? secondary.getText() : "");
        List<InlineFormat> mergedFormats = new ArrayList<>(primary.getFormats());
        for (InlineFormat fmt : secondary.getFormats()) {
            mergedFormats.add(new InlineFormat(fmt.getOffset() + primaryTextLength, fmt.getLength(), fmt.getAttributes()));
        }
        primary.setText(mergedText);
        primary.setFormats(mergedFormats);
        siblings.remove(idx + 1);

        com.fasterxml.jackson.databind.node.ObjectNode enriched =
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).deepCopy();
        enriched.put("primaryNodeTextLength", primaryTextLength);
        return enriched;
    }

    private JsonNode applySetBlockType(JsonNode payload) {
        List<Integer> path = path(payload);
        String blockType = payload.get("blockType").asText();
        nodeAt(path).setType(blockType);
        return payload;
    }
}
