package io.github.rigazilla.memory.cognition.profile;

import java.util.List;

/**
 * Response from the LLM-based profile consolidation.
 * Contains the 3 core sections with content, confidence, and source memory keys.
 */
public record ProfileConsolidationResponse(
    ProfileSectionResponse profileSnapshot,
    ProfileSectionResponse activeGoals,
    ProfileSectionResponse preferences
) {
    /**
     * Individual section response from LLM.
     */
    public record ProfileSectionResponse(
        String content,
        double confidence,
        List<String> sourceMemoryKeys
    ) {}
}
