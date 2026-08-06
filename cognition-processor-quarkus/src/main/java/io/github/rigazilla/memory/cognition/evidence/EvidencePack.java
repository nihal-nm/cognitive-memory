package io.github.rigazilla.memory.cognition.evidence;

import com.google.protobuf.ByteString;
import io.github.chirino.memory.grpc.v1.Entry;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
     * Returns the earliest {@code created_at} timestamp across all transcript entries,
     * as an ISO-8601 string.  Used as the {@code observed_at} baseline when writing memories.
     * Returns an empty Optional when there are no entries or none have a created_at value.
     */
    public Optional<String> earliestCreatedAt() {
        return transcriptEntries.stream()
            .map(Entry::getCreatedAt)
            .filter(s -> s != null && !s.isBlank())
            .min(Comparator.naturalOrder());
    }

    /**
     * A single indexed transcript entry, produced by one shared filtering pass.
     * Carries everything needed by both formatAsText() and getEntryIdMapping().
     */
    private record IndexedEntry(int index, String entryId, String role, String text, String createdAt) {}

    /**
     * Single filtering pass over transcriptEntries that produces IndexedEntry records.
     * Both formatAsText() and getEntryIdMapping() derive their output from this list,
     * ensuring the E<n> indices are always in sync.
     */
    private List<IndexedEntry> buildIndexedEntries() {
        List<IndexedEntry> result = new java.util.ArrayList<>();
        int index = 1;
        for (Entry entry : transcriptEntries) {
            String contentType = entry.getContentType();
            if (contentType != null && contentType.startsWith("history") && entry.getContentCount() > 0) {
                var content = entry.getContent(0);
                if (content.hasStructValue()) {
                    var struct = content.getStructValue();
                    String text = extractTextFromStruct(struct);
                    if (!text.isEmpty()) {
                        String role = struct.getFieldsOrDefault("role",
                            com.google.protobuf.Value.newBuilder().setStringValue("UNKNOWN").build())
                            .getStringValue();
                        String createdAt = entry.getCreatedAt();
                        String entryId = bytesToUuid(entry.getId());
                        result.add(new IndexedEntry(index, entryId, role, text, createdAt));
                        index++;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Format evidence as text for LLM consumption.
     * Converts protobuf entries to readable conversation format.
     * Each entry is prefixed with [E1], [E2], etc. for citation tracking.
     */
    public String formatAsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CONVERSATION TRANSCRIPT ===\n\n");
        for (IndexedEntry e : buildIndexedEntries()) {
            if (e.createdAt() != null && !e.createdAt().isEmpty()) {
                sb.append(String.format("[E%d] [%s] [%s] %s\n\n", e.index(), e.createdAt(), e.role(), e.text()));
            } else {
                sb.append(String.format("[E%d] [%s] %s\n\n", e.index(), e.role(), e.text()));
            }
        }
        return sb.toString();
    }

    /**
     * Get mapping from short entry references (E1, E2, etc.) to actual entry UUIDs.
     * Used to resolve entry IDs from LLM citations back to actual entry IDs.
     *
     * @return Map of "E1" -> "uuid-string", "E2" -> "uuid-string", etc.
     */
    public Map<String, String> getEntryIdMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (IndexedEntry e : buildIndexedEntries()) {
            mapping.put("E" + e.index(), e.entryId());
        }
        return mapping;
    }

    /**
     * Convert protobuf ByteString (16-byte big-endian) to UUID string.
     */
    private String bytesToUuid(ByteString bytes) {
        if (bytes.size() != 16) {
            return "(invalid-uuid)";
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();
        return new UUID(mostSigBits, leastSigBits).toString();
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
