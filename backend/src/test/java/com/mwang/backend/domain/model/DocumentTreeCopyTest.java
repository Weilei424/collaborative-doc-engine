package com.mwang.backend.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTreeCopyTest {

    @Test
    void copyProducesStructurallyEqualButReferenceDistinctTree() {
        InlineFormat fmt = new InlineFormat(2, 5, new LinkedHashMap<>(Map.of("bold", true)));
        DocumentNode leaf = DocumentNode.builder()
                .type("paragraph").text("hello")
                .formats(new ArrayList<>(List.of(fmt)))
                .build();
        DocumentTree original = DocumentTree.builder()
                .children(new ArrayList<>(List.of(leaf)))
                .build();

        DocumentTree copy = original.copy();

        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getChildren()).isNotSameAs(original.getChildren());
        assertThat(copy.getChildren().get(0)).isNotSameAs(original.getChildren().get(0));
        assertThat(copy.getChildren().get(0).getText()).isEqualTo("hello");
        assertThat(copy.getChildren().get(0).getFormats()).isNotSameAs(original.getChildren().get(0).getFormats());
        assertThat(copy.getChildren().get(0).getFormats().get(0)).isNotSameAs(fmt);
    }

    @Test
    void mutatingCopyTextDoesNotAffectOriginal() {
        DocumentNode leaf = DocumentNode.builder().type("paragraph").text("hello").build();
        DocumentTree original = DocumentTree.builder()
                .children(new ArrayList<>(List.of(leaf)))
                .build();

        DocumentTree copy = original.copy();
        copy.getChildren().get(0).setText("world");

        assertThat(original.getChildren().get(0).getText()).isEqualTo("hello");
    }

    @Test
    void mutatingCopyFormatsDoesNotAffectOriginal() {
        InlineFormat fmt = new InlineFormat(0, 3, new LinkedHashMap<>(Map.of("italic", true)));
        DocumentNode leaf = DocumentNode.builder()
                .type("paragraph").text("hi!")
                .formats(new ArrayList<>(List.of(fmt)))
                .build();
        DocumentTree original = DocumentTree.builder()
                .children(new ArrayList<>(List.of(leaf)))
                .build();

        DocumentTree copy = original.copy();
        copy.getChildren().get(0).getFormats().clear();

        assertThat(original.getChildren().get(0).getFormats()).hasSize(1);
    }

    @Test
    void mutatingCopyFormatAttributesDoesNotAffectOriginal() {
        InlineFormat fmt = new InlineFormat(0, 3, new LinkedHashMap<>(Map.of("bold", true)));
        DocumentNode leaf = DocumentNode.builder()
                .type("paragraph").text("hi!")
                .formats(new ArrayList<>(List.of(fmt)))
                .build();
        DocumentTree original = DocumentTree.builder()
                .children(new ArrayList<>(List.of(leaf)))
                .build();

        DocumentTree copy = original.copy();
        copy.getChildren().get(0).getFormats().get(0).getAttributes().put("bold", false);

        assertThat(original.getChildren().get(0).getFormats().get(0).getAttributes().get("bold"))
                .isEqualTo(true);
    }

    @Test
    void addingChildToChildrenListOfCopyDoesNotAffectOriginal() {
        DocumentNode leaf = DocumentNode.builder().type("paragraph").text("a").build();
        DocumentTree original = DocumentTree.builder()
                .children(new ArrayList<>(List.of(leaf)))
                .build();

        DocumentTree copy = original.copy();
        copy.getChildren().add(DocumentNode.builder().type("paragraph").text("b").build());

        assertThat(original.getChildren()).hasSize(1);
    }

    @Test
    void copyOfEmptyTreeProducesEmptyTree() {
        DocumentTree original = DocumentTree.builder().children(new ArrayList<>()).build();
        DocumentTree copy = original.copy();
        assertThat(copy.getChildren()).isEmpty();
        assertThat(copy.getChildren()).isNotSameAs(original.getChildren());
    }

    @Test
    void mutatingNestedChildNodeTextDoesNotAffectOriginal() {
        DocumentNode grandchild = DocumentNode.builder().type("paragraph").text("nested").build();
        DocumentNode parent = DocumentNode.builder()
                .type("block")
                .children(new ArrayList<>(List.of(grandchild)))
                .build();
        DocumentTree original = DocumentTree.builder()
                .children(new ArrayList<>(List.of(parent)))
                .build();

        DocumentTree copy = original.copy();
        copy.getChildren().get(0).getChildren().get(0).setText("mutated");

        assertThat(original.getChildren().get(0).getChildren().get(0).getText()).isEqualTo("nested");
    }
}
