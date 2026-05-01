package com.mwang.backend.collaboration;

import com.mwang.backend.domain.model.DocumentNode;
import com.mwang.backend.domain.model.DocumentTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTreeCacheTest {

    private DocumentTreeCache cache;
    private UUID docId;

    @BeforeEach
    void setUp() {
        cache = new DocumentTreeCache(100, 300);
        docId = UUID.randomUUID();
    }

    @Test
    void getMissReturnsEmpty() {
        assertThat(cache.get(docId, 1L)).isEmpty();
    }

    @Test
    void putThenGetReturnsCopyWithSameStructure() {
        DocumentTree tree = treeWithText("hello");
        cache.put(docId, 1L, tree);

        Optional<DocumentTree> result = cache.get(docId, 1L);

        assertThat(result).isPresent();
        assertThat(result.get().getChildren().get(0).getText()).isEqualTo("hello");
    }

    @Test
    void getCopyIsNotSameReferenceAsCachedTree() {
        DocumentTree tree = treeWithText("hello");
        cache.put(docId, 1L, tree);

        DocumentTree copy1 = cache.get(docId, 1L).orElseThrow();
        DocumentTree copy2 = cache.get(docId, 1L).orElseThrow();

        assertThat(copy1).isNotSameAs(tree);
        assertThat(copy1).isNotSameAs(copy2);
    }

    @Test
    void mutatingReturnedCopyDoesNotCorruptCachedEntry() {
        DocumentTree tree = treeWithText("original");
        cache.put(docId, 1L, tree);

        DocumentTree copy = cache.get(docId, 1L).orElseThrow();
        copy.getChildren().get(0).setText("mutated");

        DocumentTree secondGet = cache.get(docId, 1L).orElseThrow();
        assertThat(secondGet.getChildren().get(0).getText()).isEqualTo("original");
    }

    @Test
    void mutatingTreeAfterPutDoesNotCorruptCachedEntry() {
        DocumentTree tree = treeWithText("original");
        cache.put(docId, 1L, tree);
        tree.getChildren().get(0).setText("mutated after put");

        DocumentTree cached = cache.get(docId, 1L).orElseThrow();
        assertThat(cached.getChildren().get(0).getText()).isEqualTo("original");
    }

    @Test
    void evictRemovesEntry() {
        cache.put(docId, 1L, treeWithText("hello"));
        cache.evict(docId, 1L);
        assertThat(cache.get(docId, 1L)).isEmpty();
    }

    @Test
    void differentDocumentIdsAreIndependent() {
        UUID docId2 = UUID.randomUUID();
        cache.put(docId, 1L, treeWithText("doc1"));
        cache.put(docId2, 1L, treeWithText("doc2"));

        assertThat(cache.get(docId, 1L).orElseThrow().getChildren().get(0).getText()).isEqualTo("doc1");
        assertThat(cache.get(docId2, 1L).orElseThrow().getChildren().get(0).getText()).isEqualTo("doc2");
    }

    @Test
    void differentVersionsForSameDocumentAreIndependent() {
        cache.put(docId, 1L, treeWithText("v1"));
        cache.put(docId, 2L, treeWithText("v2"));

        assertThat(cache.get(docId, 1L).orElseThrow().getChildren().get(0).getText()).isEqualTo("v1");
        assertThat(cache.get(docId, 2L).orElseThrow().getChildren().get(0).getText()).isEqualTo("v2");
    }

    @Test
    void shortTtlExpiresEntry() throws InterruptedException {
        DocumentTreeCache shortTtlCache = new DocumentTreeCache(100, 1); // 1-second TTL
        shortTtlCache.put(docId, 1L, treeWithText("ephemeral"));

        Thread.sleep(1200);

        assertThat(shortTtlCache.get(docId, 1L)).isEmpty();
    }

    private static DocumentTree treeWithText(String text) {
        DocumentNode node = DocumentNode.builder()
                .type("paragraph").text(text).build();
        return DocumentTree.builder()
                .children(new ArrayList<>(List.of(node)))
                .build();
    }
}
