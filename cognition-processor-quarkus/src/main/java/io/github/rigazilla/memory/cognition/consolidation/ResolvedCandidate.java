package io.github.rigazilla.memory.cognition.consolidation;

import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;

import java.util.Objects;
import java.util.Optional;

/**
 * A memory candidate resolved through duplicate detection.
 * <p>
 * Wraps a {@link MemoryCandidate} with optional existing-storage metadata so that
 * {@link io.github.rigazilla.memory.cognition.writer.MemoryWriter} can perform a
 * guarded upsert (PutMemory + expected_revision) instead of a blind insert when a
 * duplicate already exists in memory-service.
 * <p>
 * {@code MemoryCandidate} is intentionally kept clean; key/revision travel here.
 */
public record ResolvedCandidate(
        MemoryCandidate candidate,
        Optional<String> existingKey,
        Optional<Long> expectedRevision
) {

    public ResolvedCandidate {
        Objects.requireNonNull(candidate, "candidate must not be null");
        existingKey      = Objects.requireNonNullElse(existingKey, Optional.empty());
        expectedRevision = Objects.requireNonNullElse(expectedRevision, Optional.empty());
    }

    /**
     * Factory for a brand-new memory (no duplicate found).
     * The writer will generate a fresh UUID key.
     */
    public static ResolvedCandidate fresh(MemoryCandidate c) {
        return new ResolvedCandidate(c, Optional.empty(), Optional.empty());
    }

    /**
     * Factory for a duplicate that maps to an existing memory entry.
     * The writer will reuse {@code existingKey} and guard the write with
     * {@code expectedRevision}.
     */
    public static ResolvedCandidate merged(MemoryCandidate c, String existingKey, long expectedRevision) {
        return new ResolvedCandidate(c, Optional.of(existingKey), Optional.of(expectedRevision));
    }

    /** True when this resolved candidate should overwrite an existing entry. */
    public boolean isUpdate() {
        return existingKey.isPresent();
    }

    @Override
    public String toString() {
        return String.format("ResolvedCandidate{type=%s, update=%s, key=%s}",
                candidate.type(), isUpdate(), existingKey.orElse("(new)"));
    }
}
