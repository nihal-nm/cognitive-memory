package io.github.rigazilla.memory.cognition.model;

import io.github.rigazilla.memory.cognition.event.ScopeJob;
import io.github.rigazilla.memory.cognition.evidence.EvidencePack;

import java.time.Instant;
import java.util.List;

/**
 * Provenance information tracking the origin of extracted memories.
 * Contains denormalized entry IDs and batch metadata for replay and audit.
 *
 * This is a self-contained snapshot of what went into the extraction,
 * following Enhancement 099 specification.
 */
public record Provenance(
    // Batch identification
    String conversationId,
    List<String> entryIds,           // Denormalized from ScopeJob batch
    String firstEventCursor,
    String latestEventCursor,
    String batchTrigger,             // Why batch was promoted (debounce_delay, max_batch_age, etc.)

    // Evidence pack fingerprint
    String sourceHash,               // Hash of canonicalized evidence pack
    String evidenceBaseId,           // ID of compacted evidence base (if used)
    String evidenceBaseHash,         // Hash of compacted evidence base (if used)

    // Runtime attribution
    String runtimeId,
    String runtimeVersion,
    Instant processedAt
) {

    /**
     * Create provenance from a ScopeJob and EvidencePack.
     *
     * @param job The scope job being processed
     * @param evidence The evidence pack used for extraction
     * @param runtimeId Runtime identifier
     * @param runtimeVersion Runtime version
     * @return Provenance record
     */
    public static Provenance fromScopeJob(
            ScopeJob job,
            EvidencePack evidence,
            String runtimeId,
            String runtimeVersion) {
        return new Provenance(
            job.conversationId(),
            job.entryIds(),
            job.firstEventCursor(),
            job.latestEventCursor(),
            job.trigger(),
            evidence.computeHash(),
            evidence.getEvidenceBaseId(),
            evidence.getEvidenceBaseHash(),
            runtimeId,
            runtimeVersion,
            Instant.now()
        );
    }

    /**
     * Create minimal provenance for Phase 3A (before source hashing is implemented).
     *
     * @param job The scope job being processed
     * @param runtimeId Runtime identifier
     * @param runtimeVersion Runtime version
     * @return Provenance record with null hash fields
     */
    public static Provenance fromScopeJobMinimal(
            ScopeJob job,
            String runtimeId,
            String runtimeVersion) {
        return new Provenance(
            job.conversationId(),
            job.entryIds(),
            job.firstEventCursor(),
            job.latestEventCursor(),
            job.trigger(),
            null,  // sourceHash - to be implemented
            null,  // evidenceBaseId - no compaction yet
            null,  // evidenceBaseHash - no compaction yet
            runtimeId,
            runtimeVersion,
            Instant.now()
        );
    }
}
