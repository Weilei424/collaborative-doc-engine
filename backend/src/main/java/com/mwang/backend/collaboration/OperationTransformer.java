package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mwang.backend.domain.DocumentOperationType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class OperationTransformer {

    /**
     * Transforms the incoming operation against an accepted concurrent operation.
     * Returns Optional.empty() if the incoming op resolves to a no-op.
     * Throws OperationConflictException for unresolvable structural conflicts.
     * Throws IllegalArgumentException if NO_OP is passed as incoming.
     */
    public Optional<JsonNode> transform(
            DocumentOperationType incomingType, JsonNode incomingPayload,
            DocumentOperationType acceptedType, JsonNode acceptedPayload) {

        if (incomingType == DocumentOperationType.NO_OP) {
            throw new IllegalArgumentException("NO_OP is not a valid incoming operation type");
        }
        if (acceptedType == DocumentOperationType.NO_OP) {
            return Optional.of(incomingPayload);
        }

        return switch (incomingType) {
            case INSERT_TEXT  -> transformInsertText(incomingPayload, acceptedType, acceptedPayload);
            case DELETE_RANGE -> transformDeleteRange(incomingPayload, acceptedType, acceptedPayload);
            case FORMAT_RANGE -> transformFormatRange(incomingPayload, acceptedType, acceptedPayload);
            case SPLIT_BLOCK  -> transformSplitBlock(incomingPayload, acceptedType, acceptedPayload);
            case MERGE_BLOCK  -> transformMergeBlock(incomingPayload, acceptedType, acceptedPayload);
            case SET_BLOCK_TYPE -> transformSetBlockType(incomingPayload, acceptedType, acceptedPayload);
            case NO_OP -> throw new IllegalArgumentException("Unreachable");
        };
    }

    // ---- Path utilities ----

    List<Integer> getPath(JsonNode payload) {
        List<Integer> result = new ArrayList<>();
        payload.get("path").forEach(n -> result.add(n.asInt()));
        return result;
    }

    boolean samePath(List<Integer> a, List<Integer> b) {
        return a.equals(b);
    }

    /**
     * Returns true if candidate and pivot share the same parent and
     * candidate's index at pivot's depth is strictly greater than pivot's.
     */
    boolean siblingAfter(List<Integer> candidate, List<Integer> pivot) {
        if (candidate.size() < pivot.size()) return false;
        for (int i = 0; i < pivot.size() - 1; i++) {
            if (!candidate.get(i).equals(pivot.get(i))) return false;
        }
        return candidate.get(pivot.size() - 1) > pivot.get(pivot.size() - 1);
    }

    /**
     * Adjusts candidate path when a SPLIT_BLOCK fires at splitPath.
     * Siblings after the split get their index incremented at the split depth.
     * If candidate == splitPath, returns unchanged (caller handles offset logic separately).
     */
    List<Integer> adjustPathForSplit(List<Integer> candidate, List<Integer> splitPath) {
        if (candidate.size() < splitPath.size()) return candidate;
        int depth = splitPath.size() - 1;
        for (int i = 0; i < depth; i++) {
            if (!candidate.get(i).equals(splitPath.get(i))) return candidate;
        }
        if (candidate.get(depth) > splitPath.get(depth)) {
            List<Integer> adjusted = new ArrayList<>(candidate);
            adjusted.set(depth, candidate.get(depth) + 1);
            return adjusted;
        }
        return candidate;
    }

    /**
     * Adjusts candidate path when MERGE_BLOCK fires at mergePath.
     * The sibling at mergePath+1 is removed; all later siblings shift down.
     * If candidate == siblingPath (mergePath+1), caller handles offset logic separately.
     */
    List<Integer> adjustPathForMerge(List<Integer> candidate, List<Integer> mergePath) {
        if (candidate.size() < mergePath.size()) return candidate;
        int depth = mergePath.size() - 1;
        for (int i = 0; i < depth; i++) {
            if (!candidate.get(i).equals(mergePath.get(i))) return candidate;
        }
        int siblingIdx = mergePath.get(depth) + 1;
        if (candidate.get(depth) > siblingIdx) {
            List<Integer> adjusted = new ArrayList<>(candidate);
            adjusted.set(depth, candidate.get(depth) - 1);
            return adjusted;
        }
        return candidate;
    }

    List<Integer> siblingPath(List<Integer> path, int offset) {
        List<Integer> result = new ArrayList<>(path);
        result.set(result.size() - 1, result.get(result.size() - 1) + offset);
        return result;
    }

    ObjectNode withPath(JsonNode payload, List<Integer> newPath) {
        ObjectNode copy = ((ObjectNode) payload).deepCopy();
        ArrayNode arr = copy.putArray("path");
        newPath.forEach(arr::add);
        return copy;
    }

    ObjectNode withPathAndOffset(JsonNode payload, List<Integer> newPath, int newOffset) {
        ObjectNode copy = withPath(payload, newPath);
        copy.put("offset", newOffset);
        return copy;
    }

    // ---- SET_BLOCK_TYPE (incoming) vs anything ----

    private Optional<JsonNode> transformSetBlockType(JsonNode incoming, DocumentOperationType acceptedType, JsonNode accepted) {
        // SET_BLOCK_TYPE carries no offset — only path adjustment needed
        List<Integer> incomingPath = getPath(incoming);
        return switch (acceptedType) {
            case INSERT_TEXT, DELETE_RANGE, FORMAT_RANGE -> Optional.of(incoming); // no structural change
            case SET_BLOCK_TYPE -> Optional.of(incoming); // last-writer-wins — both accepted
            case SPLIT_BLOCK -> {
                List<Integer> splitPath = getPath(accepted);
                if (samePath(incomingPath, splitPath)) {
                    yield Optional.of(incoming); // targets the first half, path unchanged
                }
                List<Integer> adjusted = adjustPathForSplit(incomingPath, splitPath);
                yield Optional.of(samePath(adjusted, incomingPath) ? incoming : withPath(incoming, adjusted));
            }
            case MERGE_BLOCK -> {
                List<Integer> mergePath = getPath(accepted);
                List<Integer> siblingPath = siblingPath(mergePath, 1);
                if (samePath(incomingPath, siblingPath)) {
                    yield Optional.of(withPath(incoming, mergePath)); // sibling collapsed into primary
                }
                List<Integer> adjusted = adjustPathForMerge(incomingPath, mergePath);
                yield Optional.of(samePath(adjusted, incomingPath) ? incoming : withPath(incoming, adjusted));
            }
            case NO_OP -> throw new IllegalArgumentException("NO_OP accepted type should be filtered at transform() level");
        };
    }

    // ---- Text operation transforms (Task 9) ----

    private Optional<JsonNode> transformInsertText(JsonNode in, DocumentOperationType at, JsonNode ac) {
        List<Integer> inPath = getPath(in);
        int inOffset = in.get("offset").asInt();

        return switch (at) {
            case INSERT_TEXT -> {
                if (!samePath(inPath, getPath(ac))) yield Optional.of(in);
                int acOffset = ac.get("offset").asInt();
                if (acOffset <= inOffset) {
                    int shift = ac.get("text").asText().length();
                    yield Optional.of(withPathAndOffset(in, inPath, inOffset + shift));
                }
                yield Optional.of(in);
            }
            case DELETE_RANGE -> {
                if (!samePath(inPath, getPath(ac))) yield Optional.of(in);
                int acOffset = ac.get("offset").asInt();
                int acLen = ac.get("length").asInt();
                if (acOffset + acLen <= inOffset) {
                    yield Optional.of(withPathAndOffset(in, inPath, inOffset - acLen));
                } else if (acOffset <= inOffset) {
                    yield Optional.of(withPathAndOffset(in, inPath, acOffset)); // clamp inside deleted region
                }
                yield Optional.of(in); // accepted delete is after incoming insert
            }
            case FORMAT_RANGE -> Optional.of(in); // no structural change
            case SET_BLOCK_TYPE -> Optional.of(in);
            case SPLIT_BLOCK -> {
                List<Integer> splitPath = getPath(ac);
                int splitOffset = ac.get("offset").asInt();
                if (samePath(inPath, splitPath)) {
                    if (inOffset <= splitOffset) yield Optional.of(in); // stays in first half
                    // moves to second half (new sibling)
                    List<Integer> newPath = siblingPath(splitPath, 1);
                    yield Optional.of(withPathAndOffset(in, newPath, inOffset - splitOffset));
                }
                List<Integer> adjusted = adjustPathForSplit(inPath, splitPath);
                yield Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
            }
            case MERGE_BLOCK -> {
                List<Integer> mergePath = getPath(ac);
                List<Integer> sibPath = siblingPath(mergePath, 1);
                int primaryLen = ac.has("primaryNodeTextLength") ? ac.get("primaryNodeTextLength").asInt() : 0;
                if (samePath(inPath, sibPath)) {
                    // insert was in the now-merged sibling — move to primary with offset shift
                    yield Optional.of(withPathAndOffset(in, mergePath, inOffset + primaryLen));
                }
                List<Integer> adjusted = adjustPathForMerge(inPath, mergePath);
                yield Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
            }
            case NO_OP -> throw new IllegalArgumentException("NO_OP accepted type should be filtered at transform() level");
        };
    }

    private Optional<JsonNode> transformDeleteRange(JsonNode in, DocumentOperationType at, JsonNode ac) {
        return switch (at) {
            case INSERT_TEXT  -> adjustRangeAgainstInsertText(in, ac);
            case DELETE_RANGE -> adjustRangeAgainstDeleteRange(in, ac);
            case FORMAT_RANGE -> Optional.of(in);
            case SET_BLOCK_TYPE -> Optional.of(in);
            case SPLIT_BLOCK  -> adjustRangeAgainstSplitBlock(in, ac);
            case MERGE_BLOCK  -> adjustRangeAgainstMergeBlock(in, ac);
            case NO_OP -> throw new IllegalArgumentException("NO_OP accepted type should be filtered at transform() level");
        };
    }

    private Optional<JsonNode> transformFormatRange(JsonNode in, DocumentOperationType at, JsonNode ac) {
        return switch (at) {
            case INSERT_TEXT  -> adjustRangeAgainstInsertText(in, ac);
            case DELETE_RANGE -> adjustRangeAgainstDeleteRange(in, ac);
            case FORMAT_RANGE -> Optional.of(in); // last-writer-wins per attribute, both accepted
            case SET_BLOCK_TYPE -> Optional.of(in);
            case SPLIT_BLOCK  -> adjustRangeAgainstSplitBlock(in, ac);
            case MERGE_BLOCK  -> adjustRangeAgainstMergeBlock(in, ac);
            case NO_OP -> throw new IllegalArgumentException("NO_OP accepted type should be filtered at transform() level");
        };
    }

    /**
     * Adjusts a ranged op (DELETE_RANGE or FORMAT_RANGE) — which has offset + length fields —
     * against an accepted INSERT_TEXT on the same path.
     * Returns the adjusted payload, or the original if paths differ.
     */
    private Optional<JsonNode> adjustRangeAgainstInsertText(JsonNode in, JsonNode ac) {
        List<Integer> inPath = getPath(in);
        if (!samePath(inPath, getPath(ac))) return Optional.of(in);
        int inOffset = in.get("offset").asInt();
        int inLen = in.get("length").asInt();
        int acOffset = ac.get("offset").asInt();
        int acTextLen = ac.get("text").asText().length();
        ObjectNode copy = (ObjectNode) in.deepCopy();
        if (acOffset <= inOffset) {
            copy.put("offset", inOffset + acTextLen);
        } else if (acOffset < inOffset + inLen) {
            copy.put("length", inLen + acTextLen);
        }
        return Optional.of(copy);
    }

    /**
     * Adjusts a ranged op (DELETE_RANGE or FORMAT_RANGE) against an accepted DELETE_RANGE on the same path.
     * Returns Optional.empty() if the range is fully consumed; otherwise returns the adjusted payload.
     */
    private Optional<JsonNode> adjustRangeAgainstDeleteRange(JsonNode in, JsonNode ac) {
        List<Integer> inPath = getPath(in);
        if (!samePath(inPath, getPath(ac))) return Optional.of(in);
        int inOffset = in.get("offset").asInt();
        int inLen = in.get("length").asInt();
        int acOffset = ac.get("offset").asInt();
        int acLen = ac.get("length").asInt();
        int inEnd = inOffset + inLen;
        int acEnd = acOffset + acLen;
        if (acOffset >= inEnd) return Optional.of(in);
        if (acEnd <= inOffset) {
            return Optional.of(withPathAndOffset(in, inPath, inOffset - acLen));
        }
        if (acOffset <= inOffset && acEnd >= inEnd) return Optional.empty();
        ObjectNode copy = (ObjectNode) in.deepCopy();
        if (acOffset <= inOffset) {
            int consumed = acEnd - inOffset;
            copy.put("offset", acOffset);
            copy.put("length", inLen - consumed);
        } else if (acEnd >= inEnd) {
            copy.put("length", acOffset - inOffset);
        } else {
            copy.put("length", inLen - acLen);
        }
        return Optional.of(copy);
    }

    /**
     * Adjusts a ranged op's path/offset when an accepted SPLIT_BLOCK has fired.
     * Handles same-path (clip-or-relocate) and different-path (sibling index shift) cases.
     */
    private Optional<JsonNode> adjustRangeAgainstSplitBlock(JsonNode in, JsonNode ac) {
        List<Integer> inPath = getPath(in);
        int inOffset = in.get("offset").asInt();
        int inLen = in.get("length").asInt();
        List<Integer> splitPath = getPath(ac);
        int splitOffset = ac.get("offset").asInt();
        if (samePath(inPath, splitPath)) {
            if (inOffset >= splitOffset) {
                List<Integer> newPath = siblingPath(splitPath, 1);
                return Optional.of(withPathAndOffset(in, newPath, inOffset - splitOffset));
            }
            if (inOffset + inLen > splitOffset) {
                ObjectNode copy = (ObjectNode) in.deepCopy();
                copy.put("length", splitOffset - inOffset);
                return Optional.of(copy);
            }
            return Optional.of(in);
        }
        List<Integer> adjusted = adjustPathForSplit(inPath, splitPath);
        return Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
    }

    /**
     * Adjusts a ranged op's path/offset when an accepted MERGE_BLOCK has fired.
     */
    private Optional<JsonNode> adjustRangeAgainstMergeBlock(JsonNode in, JsonNode ac) {
        List<Integer> inPath = getPath(in);
        int inOffset = in.get("offset").asInt();
        List<Integer> mergePath = getPath(ac);
        List<Integer> sibPath = siblingPath(mergePath, 1);
        int primaryLen = ac.has("primaryNodeTextLength") ? ac.get("primaryNodeTextLength").asInt() : 0;
        if (samePath(inPath, sibPath)) {
            return Optional.of(withPathAndOffset(in, mergePath, inOffset + primaryLen));
        }
        List<Integer> adjusted = adjustPathForMerge(inPath, mergePath);
        return Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
    }
    private Optional<JsonNode> transformSplitBlock(JsonNode in, DocumentOperationType at, JsonNode ac) {
        List<Integer> inPath = getPath(in);
        int inOffset = in.get("offset").asInt();

        return switch (at) {
            case INSERT_TEXT -> {
                if (!samePath(inPath, getPath(ac))) yield Optional.of(in); // text ops don't shift block indices
                int acOffset = ac.get("offset").asInt();
                int acLen = ac.get("text").asText().length();
                if (acOffset <= inOffset) {
                    yield Optional.of(withPathAndOffset(in, inPath, inOffset + acLen));
                }
                yield Optional.of(in);
            }
            case DELETE_RANGE -> {
                if (!samePath(inPath, getPath(ac))) yield Optional.of(in); // text ops don't shift block indices
                int acOffset = ac.get("offset").asInt();
                int acLen = ac.get("length").asInt();
                if (acOffset + acLen <= inOffset) {
                    yield Optional.of(withPathAndOffset(in, inPath, inOffset - acLen));
                } else if (acOffset <= inOffset) {
                    yield Optional.of(withPathAndOffset(in, inPath, acOffset));
                }
                yield Optional.of(in);
            }
            case FORMAT_RANGE -> Optional.of(in);
            case SET_BLOCK_TYPE -> Optional.of(in);
            case SPLIT_BLOCK -> {
                List<Integer> acPath = getPath(ac);
                int acOffset = ac.get("offset").asInt();
                if (samePath(inPath, acPath)) {
                    if (inOffset <= acOffset) yield Optional.of(in);
                    List<Integer> newPath = siblingPath(acPath, 1);
                    yield Optional.of(withPathAndOffset(in, newPath, inOffset - acOffset));
                }
                List<Integer> adjusted = adjustPathForSplit(inPath, acPath);
                yield Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
            }
            case MERGE_BLOCK -> {
                List<Integer> mergePath = getPath(ac);
                List<Integer> sibPath = siblingPath(mergePath, 1);
                int primaryLen = ac.has("primaryNodeTextLength") ? ac.get("primaryNodeTextLength").asInt() : 0;
                if (samePath(inPath, sibPath)) {
                    yield Optional.of(withPathAndOffset(in, mergePath, inOffset + primaryLen));
                }
                List<Integer> adjusted = adjustPathForMerge(inPath, mergePath);
                yield Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
            }
            case NO_OP -> throw new IllegalArgumentException("NO_OP accepted type should be filtered at transform() level");
        };
    }

    private Optional<JsonNode> transformMergeBlock(JsonNode in, DocumentOperationType at, JsonNode ac) {
        List<Integer> inPath = getPath(in);

        return switch (at) {
            case INSERT_TEXT, DELETE_RANGE, FORMAT_RANGE -> Optional.of(in);
            case SET_BLOCK_TYPE -> Optional.of(in);
            case SPLIT_BLOCK -> {
                List<Integer> acPath = getPath(ac);
                List<Integer> adjusted = adjustPathForSplit(inPath, acPath);
                yield Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
            }
            case MERGE_BLOCK -> {
                List<Integer> acPath = getPath(ac);
                List<Integer> acSibPath = siblingPath(acPath, 1);
                if (samePath(inPath, acPath)) {
                    yield Optional.empty();
                }
                if (samePath(inPath, acSibPath)) {
                    yield Optional.of(withPath(in, acPath));
                }
                List<Integer> adjusted = adjustPathForMerge(inPath, acPath);
                yield Optional.of(samePath(adjusted, inPath) ? in : withPath(in, adjusted));
            }
            case NO_OP -> throw new IllegalArgumentException("NO_OP accepted type should be filtered at transform() level");
        };
    }
}
