package io.github.rigazilla.memory.cognition.metadata;

import java.util.List;

/**
 * Structured response from the LLM metadata extractor.
 * Contains entities (with types) and topics (optionally hierarchical).
 */
public record MetadataExtractionResponse(
    List<ExtractedEntity> entities,
    List<String> topics
) {
    public MetadataExtractionResponse {
        if (entities == null) {
            entities = List.of();
        }
        if (topics == null) {
            topics = List.of();
        }
    }
}
