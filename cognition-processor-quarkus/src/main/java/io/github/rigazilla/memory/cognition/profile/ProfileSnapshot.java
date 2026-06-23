package io.github.rigazilla.memory.cognition.profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a consolidated profile context snapshot for a user.
 * Contains 3 core sections: profile, goals, and preferences.
 * Each section includes content, confidence, and provenance.
 */
public record ProfileSnapshot(
    String userId,
    Instant generatedAt,
    String content,
    Map<String, ProfileSection> sections
) {
    /**
     * Individual section within the profile snapshot.
     */
    public record ProfileSection(
        String content,
        double confidence,
        List<String> sourceMemoryKeys
    ) {
        public ProfileSection {
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
            }
            if (sourceMemoryKeys == null) {
                sourceMemoryKeys = List.of();
            }
        }
    }
    
    public ProfileSnapshot {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
        if (sections == null) {
            sections = Map.of();
        }
    }
    
    /**
     * Get the profile snapshot section.
     */
    public ProfileSection profileSnapshot() {
        return sections.get("profile_snapshot");
    }
    
    /**
     * Get the active goals section.
     */
    public ProfileSection activeGoals() {
        return sections.get("active_goals");
    }
    
    /**
     * Get the preferences section.
     */
    public ProfileSection preferences() {
        return sections.get("preferences");
    }
}
