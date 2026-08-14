package io.github.rigazilla.memory.cognition.consolidation;

import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;

import java.util.List;

/**
 * Searches existing memories for duplicates of an incoming {@link MemoryCandidate}.
 * Exact content-string matching — see {@link ExactMatchDuplicateDetector}.
 */
public interface DuplicateDetector {

    /**
     * Returns existing memory items whose stored content exactly matches
     * {@code candidate.content()} within the user's cognition namespace for
     * the candidate's type.
     *
     * @param userId    the memory owner
     * @param candidate the newly extracted candidate to check
     * @return matching {@link MemoryItem}s, empty list if none found
     */
    List<MemoryItem> findDuplicates(String userId, MemoryCandidate candidate);
}
