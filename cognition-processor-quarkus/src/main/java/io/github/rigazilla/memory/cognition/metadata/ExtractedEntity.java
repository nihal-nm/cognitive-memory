package io.github.rigazilla.memory.cognition.metadata;

/**
 * A named entity extracted from memory content.
 * Stored as an element of the "entities" array in memory metadata.
 */
public record ExtractedEntity(
    String name,  // e.g. "Python", "AWS", "Acme Corp"
    String type   // e.g. "technology", "organization", "person", "location", "product", "concept"
) {}
