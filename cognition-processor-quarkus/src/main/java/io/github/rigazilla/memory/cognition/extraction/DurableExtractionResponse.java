package io.github.rigazilla.memory.cognition.extraction;

import java.util.List;

/**
 * Response from the durable memory extractor.
 * Contains all extracted memory candidates grouped by type.
 * All 5 memory types are extracted in a single batched LLM call.
 */
public record DurableExtractionResponse(
    List<MemoryCandidate> facts,
    List<MemoryCandidate> preferences,
    List<MemoryCandidate> procedures,
    List<MemoryCandidate> problemSolutions,
    List<MemoryCandidate> decisions
) {
    
    public DurableExtractionResponse {
        facts = facts != null ? facts : List.of();
        preferences = preferences != null ? preferences : List.of();
        procedures = procedures != null ? procedures : List.of();
        problemSolutions = problemSolutions != null ? problemSolutions : List.of();
        decisions = decisions != null ? decisions : List.of();
    }
    
    /**
     * Get all candidates across all types.
     */
    public List<MemoryCandidate> getAllCandidates() {
        return List.of(
            facts,
            preferences,
            procedures,
            problemSolutions,
            decisions
        ).stream()
            .flatMap(List::stream)
            .toList();
    }
    
    /**
     * Get total count of all candidates.
     */
    public int getTotalCount() {
        return facts.size() + preferences.size() + procedures.size() + 
               problemSolutions.size() + decisions.size();
    }
    
    @Override
    public String toString() {
        return String.format(
            "DurableExtractionResponse{facts=%d, preferences=%d, procedures=%d, problemSolutions=%d, decisions=%d, total=%d}",
            facts.size(), preferences.size(), procedures.size(), 
            problemSolutions.size(), decisions.size(), getTotalCount()
        );
    }
}
