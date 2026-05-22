package io.github.rigazilla.memory.cognition.verification;

import io.github.rigazilla.memory.cognition.evidence.EvidencePack;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;

import java.util.List;

/**
 * Request for verifying memory candidates against evidence.
 * The verifier checks that each candidate's citations exist in the evidence pack.
 */
public record VerificationRequest(
    List<MemoryCandidate> candidates,
    EvidencePack evidence
) {
    
    public VerificationRequest {
        if (candidates == null) {
            candidates = List.of();
        }
        if (evidence == null) {
            throw new IllegalArgumentException("Evidence pack cannot be null");
        }
    }
    
    /**
     * Get the evidence formatted as text for verification.
     */
    public String getEvidenceText() {
        return evidence.formatAsText();
    }
    
    @Override
    public String toString() {
        return String.format("VerificationRequest{candidates=%d, evidence=%s}",
            candidates.size(), evidence);
    }
}
