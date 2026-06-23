package io.github.rigazilla.memory.cognition.justify;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Response containing a memory with its full justification.
 * Simplified to show only user-relevant information without technical metadata.
 */
public record MemoryJustifyResponse(
    String id,
    String content,
    double confidence,
    List<String> citations,
    @JsonProperty("conversation_id") String conversationId,
    @JsonProperty("source_entries") List<EntryDetail> sourceEntries,
    @JsonProperty("created_at") Instant createdAt
) {

    /**
     * Expanded entry detail showing the actual conversation content.
     */
    public record EntryDetail(
        String role,
        String text,
        @JsonProperty("created_at") String createdAt
    ) {}
}

// Made with Bob
