package com.mwang.backend.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwang.backend.domain.DocumentOperationType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTreeTest {

    @Test
    void nodeAtReturnsTopLevelNode() {
        DocumentNode para = DocumentNode.builder().type("paragraph").text("hello").build();
        DocumentTree tree = DocumentTree.builder().children(new ArrayList<>(List.of(para))).build();
        assertThat(tree.nodeAt(List.of(0))).isSameAs(para);
    }

    @Test
    void nodeAtReturnsNestedNode() {
        DocumentNode child = DocumentNode.builder().type("list_item").text("item").build();
        DocumentNode list = DocumentNode.builder().type("bullet_list")
                .children(new ArrayList<>(List.of(child))).build();
        DocumentTree tree = DocumentTree.builder()
                .children(new ArrayList<>(List.of(list))).build();
        assertThat(tree.nodeAt(List.of(0, 0))).isSameAs(child);
    }

    @Test
    void nodeAtThrowsOnEmptyPath() {
        DocumentTree tree = DocumentTree.builder().children(new ArrayList<>()).build();
        assertThatThrownBy(() -> tree.nodeAt(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void siblingsOfReturnsTopLevelList() {
        DocumentNode para = DocumentNode.builder().type("paragraph").text("a").build();
        DocumentTree tree = DocumentTree.builder()
                .children(new ArrayList<>(List.of(para))).build();
        assertThat(tree.siblingsOf(List.of(0))).isSameAs(tree.getChildren());
    }

    @Test
    void siblingsOfReturnsChildrenOfParent() {
        DocumentNode child = DocumentNode.builder().type("list_item").text("x").build();
        DocumentNode list = DocumentNode.builder().type("bullet_list")
                .children(new ArrayList<>(List.of(child))).build();
        DocumentTree tree = DocumentTree.builder()
                .children(new ArrayList<>(List.of(list))).build();
        assertThat(tree.siblingsOf(List.of(0, 0))).isSameAs(list.getChildren());
    }

    @Test
    void applyInsertTextInsertsAtOffset() {
        DocumentNode para = DocumentNode.builder().type("paragraph").text("helo").formats(new java.util.ArrayList<>()).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        JsonNode payload = payload("{\"path\":[0],\"offset\":3,\"text\":\"l\"}");
        tree.applyOperation(DocumentOperationType.INSERT_TEXT, payload);
        assertThat(para.getText()).isEqualTo("hello");
    }

    @Test
    void applyInsertTextAdjustsFormatsAfterInsertionPoint() {
        InlineFormat fmt = new InlineFormat(5, 3, java.util.Map.of("bold", true));
        DocumentNode para = DocumentNode.builder().type("paragraph").text("hello world")
                .formats(new java.util.ArrayList<>(List.of(fmt))).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        JsonNode payload = payload("{\"path\":[0],\"offset\":2,\"text\":\"XY\"}");
        tree.applyOperation(DocumentOperationType.INSERT_TEXT, payload);
        assertThat(para.getText()).isEqualTo("heXYllo world");
        assertThat(fmt.getOffset()).isEqualTo(7); // shifted by 2
    }

    @Test
    void applyDeleteRangeDeletesSubstring() {
        DocumentNode para = DocumentNode.builder().type("paragraph").text("hello world").formats(new java.util.ArrayList<>()).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        JsonNode payload = payload("{\"path\":[0],\"offset\":5,\"length\":6}");
        tree.applyOperation(DocumentOperationType.DELETE_RANGE, payload);
        assertThat(para.getText()).isEqualTo("hello");
    }

    @Test
    void applyDeleteRangeRemovesFullyConsumedFormats() {
        InlineFormat fmt = new InlineFormat(6, 5, java.util.Map.of("bold", true));
        DocumentNode para = DocumentNode.builder().type("paragraph").text("hello world")
                .formats(new java.util.ArrayList<>(List.of(fmt))).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        JsonNode payload = payload("{\"path\":[0],\"offset\":5,\"length\":6}");
        tree.applyOperation(DocumentOperationType.DELETE_RANGE, payload);
        assertThat(para.getFormats()).isEmpty();
    }

    @Test
    void applyFormatRangeAddsFormatSpan() {
        DocumentNode para = DocumentNode.builder().type("paragraph").text("hello").formats(new java.util.ArrayList<>()).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        JsonNode payload = payload("{\"path\":[0],\"offset\":0,\"length\":5,\"attributes\":{\"bold\":true}}");
        tree.applyOperation(DocumentOperationType.FORMAT_RANGE, payload);
        assertThat(para.getFormats()).hasSize(1);
        assertThat(para.getFormats().get(0).getAttributes()).containsEntry("bold", true);
    }

    @Test
    void applySplitBlockSplitsLeafAtOffset() {
        DocumentNode para = DocumentNode.builder().type("paragraph").text("helloworld").formats(new java.util.ArrayList<>()).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        JsonNode payload = payload("{\"path\":[0],\"offset\":5}");
        tree.applyOperation(DocumentOperationType.SPLIT_BLOCK, payload);
        assertThat(tree.getChildren()).hasSize(2);
        assertThat(tree.getChildren().get(0).getText()).isEqualTo("hello");
        assertThat(tree.getChildren().get(1).getText()).isEqualTo("world");
    }

    @Test
    void applySplitBlockPreservesType() {
        DocumentNode heading = DocumentNode.builder().type("heading_1").text("Title text").formats(new java.util.ArrayList<>()).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(heading))).build();
        tree.applyOperation(DocumentOperationType.SPLIT_BLOCK, payload("{\"path\":[0],\"offset\":5}"));
        assertThat(tree.getChildren().get(1).getType()).isEqualTo("heading_1");
    }

    @Test
    void splitBlockPreservesFormattingOnBothHalves() {
        InlineFormat boldFormat = new InlineFormat(0, 11, java.util.Map.of("bold", true));
        DocumentNode para = DocumentNode.builder().type("paragraph").text("hello world")
                .formats(new java.util.ArrayList<>(List.of(boldFormat))).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        tree.applyOperation(DocumentOperationType.SPLIT_BLOCK, payload("{\"path\":[0],\"offset\":5}"));
        assertThat(tree.getChildren()).hasSize(2);
        assertThat(tree.getChildren().get(0).getFormats()).hasSize(1);
        assertThat(tree.getChildren().get(0).getFormats().get(0).getOffset()).isEqualTo(0);
        assertThat(tree.getChildren().get(0).getFormats().get(0).getLength()).isEqualTo(5);
        assertThat(tree.getChildren().get(1).getFormats()).hasSize(1);
        assertThat(tree.getChildren().get(1).getFormats().get(0).getOffset()).isEqualTo(0);
        assertThat(tree.getChildren().get(1).getFormats().get(0).getLength()).isEqualTo(6);
    }

    @Test
    void applyMergeBlockMergesWithNextSibling() {
        DocumentNode first = DocumentNode.builder().type("paragraph").text("hello").formats(new java.util.ArrayList<>()).build();
        DocumentNode second = DocumentNode.builder().type("paragraph").text(" world").formats(new java.util.ArrayList<>()).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(first, second))).build();
        JsonNode enriched = tree.applyOperation(DocumentOperationType.MERGE_BLOCK, payload("{\"path\":[0]}"));
        assertThat(tree.getChildren()).hasSize(1);
        assertThat(first.getText()).isEqualTo("hello world");
        assertThat(enriched.get("primaryNodeTextLength").asInt()).isEqualTo(5);
    }

    @Test
    void applySetBlockTypeSetsType() {
        DocumentNode para = DocumentNode.builder().type("paragraph").text("text").formats(new java.util.ArrayList<>()).build();
        DocumentTree tree = DocumentTree.builder().children(new java.util.ArrayList<>(List.of(para))).build();
        tree.applyOperation(DocumentOperationType.SET_BLOCK_TYPE, payload("{\"path\":[0],\"blockType\":\"heading_1\"}"));
        assertThat(para.getType()).isEqualTo("heading_1");
    }

    private JsonNode payload(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
