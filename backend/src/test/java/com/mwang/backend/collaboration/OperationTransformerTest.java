package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationTransformerTest {

    private OperationTransformer transformer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        transformer = new OperationTransformer();
        mapper = new ObjectMapper();
    }

    // NO_OP accepted — any incoming is unchanged
    @Test
    void transformAgainstNoOpReturnsIncomingUnchanged() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":3,\"text\":\"x\"}");
        JsonNode noOpPayload = json("{}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.NO_OP, noOpPayload);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(incoming);
    }

    // NO_OP cannot be an incoming operation
    @Test
    void transformWithNoOpIncomingThrows() throws Exception {
        JsonNode payload = json("{}");
        assertThatThrownBy(() -> transformer.transform(
                DocumentOperationType.NO_OP, payload,
                DocumentOperationType.INSERT_TEXT, json("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // SET_BLOCK_TYPE incoming vs text accepted — path-only, no offset adjustment
    @Test
    void setBlockTypeAgainstInsertTextOnDifferentPathIsUnchanged() throws Exception {
        JsonNode incoming = json("{\"path\":[1],\"blockType\":\"heading_1\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":2,\"text\":\"hi\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SET_BLOCK_TYPE, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        assertThat(result).isPresent();
        assertThat(result.get().get("blockType").asText()).isEqualTo("heading_1");
    }

    // SET_BLOCK_TYPE vs SET_BLOCK_TYPE on same path — last-writer-wins (both accepted)
    @Test
    void setBlockTypeAgainstSetBlockTypeSamePath() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"blockType\":\"heading_2\"}");
        JsonNode accepted = json("{\"path\":[0],\"blockType\":\"heading_1\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SET_BLOCK_TYPE, incoming,
                DocumentOperationType.SET_BLOCK_TYPE, accepted);
        assertThat(result).isPresent();
        assertThat(result.get().get("blockType").asText()).isEqualTo("heading_2");
    }

    // SET_BLOCK_TYPE incoming vs SPLIT_BLOCK accepted — same path → targets first half, path unchanged
    @Test
    void setBlockTypeAgainstSplitOnSamePath() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"blockType\":\"heading_1\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SET_BLOCK_TYPE, incoming,
                DocumentOperationType.SPLIT_BLOCK, accepted);
        assertThat(result).isPresent();
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(0);
    }

    // SET_BLOCK_TYPE incoming vs SPLIT_BLOCK accepted — sibling after → path incremented
    @Test
    void setBlockTypeAgainstSplitOnSiblingBefore() throws Exception {
        JsonNode incoming = json("{\"path\":[2],\"blockType\":\"heading_1\"}");
        JsonNode accepted = json("{\"path\":[1],\"offset\":3}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SET_BLOCK_TYPE, incoming,
                DocumentOperationType.SPLIT_BLOCK, accepted);
        assertThat(result).isPresent();
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(3);
    }

    // SET_BLOCK_TYPE incoming vs MERGE_BLOCK accepted — targets the removed sibling → remapped to merge target
    @Test
    void setBlockTypeAgainstMergeOnSiblingPath() throws Exception {
        JsonNode incoming = json("{\"path\":[1],\"blockType\":\"heading_1\"}");
        JsonNode accepted = json("{\"path\":[0]}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SET_BLOCK_TYPE, incoming,
                DocumentOperationType.MERGE_BLOCK, accepted);
        assertThat(result).isPresent();
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(0);
    }

    // INSERT_TEXT vs INSERT_TEXT
    @Test
    void insertTextAgainstInsertTextSamePathBefore() throws Exception {
        // accepted inserts at 2, incoming at 5 → incoming offset shifts to 5 + length("ab") = 7
        JsonNode incoming = json("{\"path\":[0],\"offset\":5,\"text\":\"x\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":2,\"text\":\"ab\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(7);
    }

    @Test
    void insertTextAgainstInsertTextSamePathAfter() throws Exception {
        // accepted inserts after incoming → incoming offset unchanged
        JsonNode incoming = json("{\"path\":[0],\"offset\":2,\"text\":\"x\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":5,\"text\":\"ab\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(2);
    }

    @Test
    void insertTextAgainstInsertTextDifferentPath() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":3,\"text\":\"x\"}");
        JsonNode accepted = json("{\"path\":[1],\"offset\":1,\"text\":\"ab\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(3);
    }

    // INSERT_TEXT vs DELETE_RANGE
    @Test
    void insertTextAgainstDeleteRangeSamePathBefore() throws Exception {
        // delete [1,3) then insert at 5 → offset shifts to 5-2=3
        JsonNode incoming = json("{\"path\":[0],\"offset\":5,\"text\":\"x\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":1,\"length\":2}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.DELETE_RANGE, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(3);
    }

    @Test
    void insertTextAgainstDeleteRangeSamePathInsideDeletedRegion() throws Exception {
        // incoming insert is inside deleted region → clamp to deletion start
        JsonNode incoming = json("{\"path\":[0],\"offset\":3,\"text\":\"x\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":1,\"length\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.DELETE_RANGE, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(1);
    }

    // DELETE_RANGE vs INSERT_TEXT
    @Test
    void deleteRangeAgainstInsertTextInsideRange() throws Exception {
        // delete [3,8), insert "ab" at 5 (inside range) → range extends by 2
        JsonNode incoming = json("{\"path\":[0],\"offset\":3,\"length\":5}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":5,\"text\":\"ab\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.DELETE_RANGE, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        assertThat(result.get().get("length").asInt()).isEqualTo(7);
    }

    // DELETE_RANGE vs DELETE_RANGE — fully consumed
    @Test
    void deleteRangeAgainstDeleteRangeFullyConsumed() throws Exception {
        // accepted deletes [0,10), incoming deletes [2,5) — already gone
        JsonNode incoming = json("{\"path\":[0],\"offset\":2,\"length\":3}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":0,\"length\":10}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.DELETE_RANGE, incoming,
                DocumentOperationType.DELETE_RANGE, accepted);
        assertThat(result).isEmpty();
    }

    @Test
    void deleteRangeAgainstDeleteRangeNonOverlappingBefore() throws Exception {
        // accepted deletes [0,2), incoming deletes [5,8) → incoming offset shifts to 3
        JsonNode incoming = json("{\"path\":[0],\"offset\":5,\"length\":3}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":0,\"length\":2}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.DELETE_RANGE, incoming,
                DocumentOperationType.DELETE_RANGE, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(3);
        assertThat(result.get().get("length").asInt()).isEqualTo(3);
    }

    // FORMAT_RANGE vs DELETE_RANGE — format range fully deleted
    @Test
    void formatRangeAgainstDeleteRangeFullyConsumed() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":2,\"length\":3,\"attributes\":{\"bold\":true}}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":0,\"length\":10}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.FORMAT_RANGE, incoming,
                DocumentOperationType.DELETE_RANGE, accepted);
        assertThat(result).isEmpty();
    }

    // Text vs SET_BLOCK_TYPE — no change
    @Test
    void insertTextAgainstSetBlockTypeIsUnchanged() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":3,\"text\":\"x\"}");
        JsonNode accepted = json("{\"path\":[0],\"blockType\":\"heading_1\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.SET_BLOCK_TYPE, accepted);
        assertThat(result.get()).isEqualTo(incoming);
    }

    // DELETE_RANGE and FORMAT_RANGE vs SET_BLOCK_TYPE — no change
    @Test
    void deleteRangeAgainstSetBlockTypeIsUnchanged() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":2,\"length\":3}");
        JsonNode accepted = json("{\"path\":[0],\"blockType\":\"heading_1\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.DELETE_RANGE, incoming,
                DocumentOperationType.SET_BLOCK_TYPE, accepted);
        assertThat(result.get()).isEqualTo(incoming);
    }

    @Test
    void formatRangeAgainstSetBlockTypeIsUnchanged() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":1,\"length\":4,\"attributes\":{\"bold\":true}}");
        JsonNode accepted = json("{\"path\":[0],\"blockType\":\"heading_1\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.FORMAT_RANGE, incoming,
                DocumentOperationType.SET_BLOCK_TYPE, accepted);
        assertThat(result.get()).isEqualTo(incoming);
    }

    // INSERT_TEXT vs INSERT_TEXT at equal offset — shift (accepted wins tie)
    @Test
    void insertTextAgainstInsertTextSamePathEqualOffset() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":5,\"text\":\"x\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":5,\"text\":\"ab\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        // acOffset (5) <= inOffset (5) → shift by length("ab") = 2
        assertThat(result.get().get("offset").asInt()).isEqualTo(7);
    }

    // Text ops vs SPLIT_BLOCK accepted — same path, offset in second half
    @Test
    void insertTextAgainstSplitBlockSamePathOffsetInSecondHalf() throws Exception {
        // insert at offset 8, split at 5 on same node → moves to sibling [1] at offset 3
        JsonNode incoming = json("{\"path\":[0],\"offset\":8,\"text\":\"z\"}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.SPLIT_BLOCK, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(1);
        assertThat(result.get().get("offset").asInt()).isEqualTo(3);
    }

    @Test
    void insertTextAgainstSplitBlockDifferentPathSiblingAfter() throws Exception {
        // insert at [2], split at [1] → incoming path shifts to [3]
        JsonNode incoming = json("{\"path\":[2],\"offset\":3,\"text\":\"z\"}");
        JsonNode accepted = json("{\"path\":[1],\"offset\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.SPLIT_BLOCK, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(3);
    }

    // Text ops vs MERGE_BLOCK accepted — incoming in merged sibling
    @Test
    void insertTextAgainstMergeBlockIncomingInMergedSibling() throws Exception {
        // merge [0]+[1], primaryLen=5; insert at [1] offset 2 → moves to [0] offset 7
        JsonNode incoming = json("{\"path\":[1],\"offset\":2,\"text\":\"z\"}");
        JsonNode accepted = json("{\"path\":[0],\"primaryNodeTextLength\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.INSERT_TEXT, incoming,
                DocumentOperationType.MERGE_BLOCK, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(0);
        assertThat(result.get().get("offset").asInt()).isEqualTo(7);
    }

    @Test
    void deleteRangeAgainstMergeBlockIncomingInMergedSibling() throws Exception {
        // merge [0]+[1], primaryLen=5; delete at [1] offset 1 length 3 → moves to [0] offset 6
        JsonNode incoming = json("{\"path\":[1],\"offset\":1,\"length\":3}");
        JsonNode accepted = json("{\"path\":[0],\"primaryNodeTextLength\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.DELETE_RANGE, incoming,
                DocumentOperationType.MERGE_BLOCK, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(0);
        assertThat(result.get().get("offset").asInt()).isEqualTo(6);
    }

    // SPLIT_BLOCK vs INSERT_TEXT same path — offset shifts
    @Test
    void splitBlockAgainstInsertTextSamePathBefore() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":5}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":2,\"text\":\"ab\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SPLIT_BLOCK, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(7);
    }

    // SPLIT_BLOCK vs DELETE_RANGE — split offset inside deleted region: clamp
    @Test
    void splitBlockAgainstDeleteRangeSplitInsideDeletedRegion() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":5}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":3,\"length\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SPLIT_BLOCK, incoming,
                DocumentOperationType.DELETE_RANGE, accepted);
        assertThat(result.get().get("offset").asInt()).isEqualTo(3);
    }

    // SPLIT_BLOCK vs SPLIT_BLOCK same node — incoming in second half
    @Test
    void splitBlockAgainstSplitBlockSameNodeAcceptedBefore() throws Exception {
        JsonNode incoming = json("{\"path\":[0],\"offset\":7}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":3}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SPLIT_BLOCK, incoming,
                DocumentOperationType.SPLIT_BLOCK, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(1);
        assertThat(result.get().get("offset").asInt()).isEqualTo(4);
    }

    // SPLIT_BLOCK vs MERGE_BLOCK — split target is merged sibling
    @Test
    void splitBlockAgainstMergeBlockSplitTargetIsMergedSibling() throws Exception {
        JsonNode incoming = json("{\"path\":[1],\"offset\":3}");
        JsonNode accepted = json("{\"path\":[0],\"primaryNodeTextLength\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.SPLIT_BLOCK, incoming,
                DocumentOperationType.MERGE_BLOCK, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(0);
        assertThat(result.get().get("offset").asInt()).isEqualTo(8);
    }

    // MERGE_BLOCK vs MERGE_BLOCK same target → no-op
    @Test
    void mergeBlockAgainstMergeBlockSameTarget() throws Exception {
        JsonNode incoming = json("{\"path\":[0]}");
        JsonNode accepted = json("{\"path\":[0],\"primaryNodeTextLength\":5}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.MERGE_BLOCK, incoming,
                DocumentOperationType.MERGE_BLOCK, accepted);
        assertThat(result).isEmpty();
    }

    // MERGE_BLOCK vs INSERT_TEXT — no path change
    @Test
    void mergeBlockAgainstInsertTextInPrimaryNodeIsUnchanged() throws Exception {
        JsonNode incoming = json("{\"path\":[0]}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":3,\"text\":\"hi\"}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.MERGE_BLOCK, incoming,
                DocumentOperationType.INSERT_TEXT, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(0);
    }

    // MERGE_BLOCK vs SPLIT_BLOCK at same path — path unchanged
    @Test
    void mergeBlockAgainstSplitBlockAtSamePath() throws Exception {
        JsonNode incoming = json("{\"path\":[0]}");
        JsonNode accepted = json("{\"path\":[0],\"offset\":3}");
        Optional<JsonNode> result = transformer.transform(
                DocumentOperationType.MERGE_BLOCK, incoming,
                DocumentOperationType.SPLIT_BLOCK, accepted);
        assertThat(result.get().get("path").get(0).asInt()).isEqualTo(0);
    }

    // Helper
    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }
}
