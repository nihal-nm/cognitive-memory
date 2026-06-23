package io.github.rigazilla.memory.cognition.profile;

import io.github.chirino.memory.grpc.v1.MemoryItem;

import java.util.List;

/**
 * Strategy interface for consolidating user memories into a profile snapshot.
 * This is the experimental boundary - implementations can use different approaches
 * (LLM-based, rule-based, hybrid) without affecting the stable infrastructure.
 */
public interface ProfileConsolidationStrategy {
    
    /**
     * Consolidate a list of user memories into a profile snapshot.
     * 
     * @param memories List of memory items from the user's cognition namespace
     * @param userId User ID for context
     * @return Consolidated profile snapshot with 3 core sections
     */
    ProfileSnapshot consolidate(List<MemoryItem> memories, String userId);
}
