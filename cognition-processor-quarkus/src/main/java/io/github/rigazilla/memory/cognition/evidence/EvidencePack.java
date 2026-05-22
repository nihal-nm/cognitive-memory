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
     * Format evidence as text for LLM consumption.
     * Converts protobuf entries to readable conversation format.
     */
    public String formatAsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CONVERSATION TRANSCRIPT ===\n\n");
        
        for (Entry entry : transcriptEntries) {
            // Extract role and text from content
            // History entries have content_type="history" with text and role fields
            if ("history".equals(entry.getContentType()) && entry.getContentCount() > 0) {
                var content = entry.getContent(0);
                if (content.hasStructValue()) {
                    var struct = content.getStructValue();
                    String role = struct.getFieldsOrDefault("role", 
                        com.google.protobuf.Value.newBuilder().setStringValue("UNKNOWN").build())
                        .getStringValue();
                    String text = struct.getFieldsOrDefault("text",
                        com.google.protobuf.Value.newBuilder().setStringValue("").build())
                        .getStringValue();
                    
                    sb.append(String.format("[%s] %s\n\n", role, text));
                }
            }
        }
        
        return sb.toString();
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
