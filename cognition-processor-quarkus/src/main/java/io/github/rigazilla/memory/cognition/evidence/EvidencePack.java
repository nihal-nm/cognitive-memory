package io.github.rigazilla.memory.cognition.evidence;

import io.github.chirino.memory.grpc.v1.Entry;

import java.util.List;

/**
 * Container for evidence used in memory extraction.
 * Phase 3A: Contains only transcript entries.
 * Future phases may add episodic memories, context, and knowledge clusters.
 */
public class EvidencePack {

    private final List<Entry> transcriptEntries;

    public EvidencePack(List<Entry> transcriptEntries) {
        this.transcriptEntries = transcriptEntries;
    }

    public List<Entry> getTranscriptEntries() {
        return transcriptEntries;
    }

    /**
     * Compute hash of canonicalized evidence pack.
     * Phase 3A: Not yet implemented - returns null.
     * Future: Will compute SHA-256 hash of normalized evidence for deduplication.
     */
    public String computeHash() {
        // TODO: Implement evidence pack hashing
        return null;
    }

    /**
     * Get ID of compacted evidence base (if used).
     * Phase 3A: No compaction yet - returns null.
     * Future: Will return evidence-base:<conversation-id> when compaction is implemented.
     */
    public String getEvidenceBaseId() {
        // TODO: Implement when compaction is added
        return null;
    }

    /**
     * Get hash of compacted evidence base (if used).
     * Phase 3A: No compaction yet - returns null.
     * Future: Will return hash of compacted base for verification.
     */
    public String getEvidenceBaseHash() {
        // TODO: Implement when compaction is added
        return null;
    }
    
    /**
     * Format evidence as text for LLM consumption.
     * Converts protobuf entries to readable conversation format.
     */
    public String formatAsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CONVERSATION TRANSCRIPT ===\n\n");

        for (Entry entry : transcriptEntries) {
            // Extract role and text from content
            // History entries have content_type="history" or "history/lc4j" or similar variants
            String contentType = entry.getContentType();
            if (contentType != null && contentType.startsWith("history") && entry.getContentCount() > 0) {
                var content = entry.getContent(0);
                if (content.hasStructValue()) {
                    var struct = content.getStructValue();
                    String role = struct.getFieldsOrDefault("role",
                        com.google.protobuf.Value.newBuilder().setStringValue("UNKNOWN").build())
                        .getStringValue();

                    // Extract text - different structure for history vs history/lc4j
                    String text = extractTextFromStruct(struct);

                    // Only include if we got actual text
                    if (!text.isEmpty()) {
                        String createdAt = entry.getCreatedAt();
                        if (createdAt != null && !createdAt.isEmpty()) {
                            sb.append(String.format("[%s] [%s] %s\n\n", createdAt, role, text));
                        } else {
                            sb.append(String.format("[%s] %s\n\n", role, text));
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Extract text from entry struct.
     * Handles both plain "history" format (text field) and "history/lc4j" format (events array).
     */
    private String extractTextFromStruct(com.google.protobuf.Struct struct) {
        // Try simple "text" field first (plain history entries)
        if (struct.containsFields("text")) {
            return struct.getFieldsOrDefault("text",
                com.google.protobuf.Value.newBuilder().setStringValue("").build())
                .getStringValue();
        }

        // Try "events" array (history/lc4j entries)
        if (struct.containsFields("events")) {
            var eventsValue = struct.getFieldsOrThrow("events");
            if (eventsValue.hasListValue()) {
                var eventsList = eventsValue.getListValue();

                // Look for "Completed" event with aiMessage.text
                for (var event : eventsList.getValuesList()) {
                    if (event.hasStructValue()) {
                        var eventStruct = event.getStructValue();

                        // Check if this is a Completed event
                        if (eventStruct.containsFields("eventType")) {
                            String eventType = eventStruct.getFieldsOrThrow("eventType").getStringValue();

                            if ("Completed".equals(eventType) && eventStruct.containsFields("aiMessage")) {
                                var aiMessage = eventStruct.getFieldsOrThrow("aiMessage");
                                if (aiMessage.hasStructValue()) {
                                    var aiMessageStruct = aiMessage.getStructValue();
                                    if (aiMessageStruct.containsFields("text")) {
                                        return aiMessageStruct.getFieldsOrThrow("text").getStringValue();
                                    }
                                }
                            }

                            // Fallback: use PartialResponse chunk if available
                            if ("PartialResponse".equals(eventType) && eventStruct.containsFields("chunk")) {
                                return eventStruct.getFieldsOrThrow("chunk").getStringValue();
                            }
                        }
                    }
                }
            }
        }

        return "";
    }
    
    /**
     * Get total number of entries in the evidence pack.
     */
    public int size() {
        return transcriptEntries.size();
    }
    
    @Override
    public String toString() {
        return String.format("EvidencePack{entries=%d}", transcriptEntries.size());
    }
}
