package io.github.rigazilla.memory.cognition.verification;

import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;

import java.util.List;

/**
 * Response from the durable memory verifier.
 * Contains verified candidates (with valid citations) and rejected candidates.
 */
public record DurableVerificationResponse(
    List<MemoryCandidate> verified,
    List<RejectedCandidate> rejected
) {
    
    public DurableVerificationResponse {
        verified = verified != null ? verified : List.of();
        rejected = rejected != null ? rejected : List.of();
    }
    
    /**
     * A candidate that was rejected during verification.
     */
    public record RejectedCandidate(
        MemoryCandidate candidate,
        String reason
    ) {
        @Override
        public String toString() {
            return String.format("RejectedCandidate{type=%s, reason='%s', content='%s'}",
                candidate.type(), reason,
                candidate.content().length() > 30 ? 
                    candidate.content().substring(0, 27) + "..." : 
                    candidate.content());
        }
    }
    
    @Override
    public String toString() {
        return String.format("DurableVerificationResponse{verified=%d, rejected=%d}",
            verified.size(), rejected.size());
    }
}
