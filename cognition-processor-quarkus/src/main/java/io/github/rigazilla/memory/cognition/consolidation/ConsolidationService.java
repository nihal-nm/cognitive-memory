package io.github.rigazilla.memory.cognition.consolidation;

import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates duplicate detection and merging for a batch of extracted
 * {@link MemoryCandidate}s before they reach the verification stage.
 * <p>
 * Pipeline position:
 * <pre>
 *   Extract → [ConsolidationService.deduplicate()] → Verify → Write
 * </pre>
 * Two-step process:
 * <ol>
 *   <li><b>Within-batch dedup</b> — collapses candidates with identical content
 *       extracted in the same LLM call (union citations, max confidence).
 *       Pure in-memory, no gRPC.</li>
 *   <li><b>Cross-batch dedup</b> — for each surviving candidate, searches
 *       existing memories via {@link DuplicateDetector}. If a match is found,
 *       merges via {@link MemoryMerger} so the writer performs a guarded upsert
 *       instead of a blind insert.</li>
 * </ol>
 * This class is stateless and owns no gRPC channel; the gRPC boundary lives
 * entirely inside {@link ExactMatchDuplicateDetector}.
 */
@ApplicationScoped
public class ConsolidationService {

    private static final Logger LOG = Logger.getLogger(ConsolidationService.class);

    @Inject
    DuplicateDetector detector;

    @Inject
    MemoryMerger merger;

    /**
     * Deduplicate a batch of candidates, returning one {@link ResolvedCandidate}
     * per surviving unique memory.
     *
     * @param userId     the memory owner (used to scope the duplicate search)
     * @param candidates raw candidates from the extractor
     * @return deduplicated, resolved list ready for verification and writing
     */
    public List<ResolvedCandidate> deduplicate(String userId, List<MemoryCandidate> candidates) {
        // Step 1: within-batch dedup
        List<MemoryCandidate> batchDeduped = deduplicateWithinBatch(candidates);
        LOG.debugf("Within-batch dedup: %d → %d candidates", candidates.size(), batchDeduped.size());

        // Step 2: cross-batch dedup — each candidate resolves to fresh or merged
        List<ResolvedCandidate> resolved = batchDeduped.stream()
                .map(candidate -> resolve(userId, candidate))
                .toList();

        long mergeCount = resolved.stream().filter(ResolvedCandidate::isUpdate).count();
        LOG.infof("Deduplication complete: %d candidates → %d resolved (%d merges, %d fresh)",
                candidates.size(), resolved.size(), mergeCount, resolved.size() - mergeCount);

        return resolved;
    }

    /**
     * Resolve a single candidate: search for duplicates, merge if found, fresh if not.
     */
    private ResolvedCandidate resolve(String userId, MemoryCandidate candidate) {
        List<MemoryItem> duplicates = detector.findDuplicates(userId, candidate);
        return duplicates.isEmpty()
                ? ResolvedCandidate.fresh(candidate)
                : merger.merge(candidate, duplicates);
    }

    /**
     * Collapse candidates with identical content within the same batch.
     * Union citations, keep max confidence. Preserves first-seen insertion order.
     */
    private List<MemoryCandidate> deduplicateWithinBatch(List<MemoryCandidate> candidates) {
        Map<String, BatchEntry> seen = new LinkedHashMap<>();

        for (MemoryCandidate candidate : candidates) {
            String key = candidate.content().strip().toLowerCase(Locale.ROOT);
            seen.computeIfAbsent(key, k -> new BatchEntry(candidate))
                .mergeIn(candidate);
        }

        return seen.values().stream()
                .map(BatchEntry::toCandidate)
                .toList();
    }

    /**
     * Accumulator for within-batch merging.
     * Stores the seed candidate for immutable identity fields (type, content);
     * only mutable state (confidence, citations) is held separately.
     */
    private static class BatchEntry {
        private final MemoryCandidate seed;
        private double confidence;
        private final Set<String> citations;

        BatchEntry(MemoryCandidate candidate) {
            this.seed       = candidate;
            this.confidence = candidate.confidence();
            this.citations  = new LinkedHashSet<>(candidate.citations());
        }

        void mergeIn(MemoryCandidate other) {
            confidence = Math.max(confidence, other.confidence());
            citations.addAll(other.citations());
        }

        MemoryCandidate toCandidate() {
            return new MemoryCandidate(
                    seed.type(), seed.content(), confidence, new ArrayList<>(citations));
        }
    }
}
