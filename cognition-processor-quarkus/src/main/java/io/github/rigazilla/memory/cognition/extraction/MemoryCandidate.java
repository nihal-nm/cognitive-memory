package io.github.rigazilla.memory.cognition.extraction;

import java.util.List;

/**
 * A candidate memory extracted from conversation evidence.
 * Contains the memory content, type, confidence, and citations.
 */
public record MemoryCandidate(
    String type,           // fact, preference, procedure, problem_solution, decision
    String content,        // The actual memory content
    double confidence,     // 0.0-1.0 confidence score
    List<String> citations // References to evidence (entry IDs or text snippets)
) {
    
    public MemoryCandidate {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Memory type cannot be null or blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Memory content cannot be null or blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
        }
        if (citations == null) {
            citations = List.of();
        }
    }
    
    @Override
    public String toString() {
        return String.format("MemoryCandidate{type=%s, confidence=%.2f, citations=%d, content='%s'}",
            type, confidence, citations.size(), 
            content.length() > 50 ? content.substring(0, 47) + "..." : content);
    }
}
