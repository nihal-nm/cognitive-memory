package io.github.rigazilla.memory.cognition.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCandidateTest {

    @Test
    void validCandidateIsRecognized() {
        var candidate = new MemoryCandidate("fact", "User prefers dark mode", 0.9, List.of("entry-1"));
        assertTrue(candidate.isValid());
    }

    @Test
    void emptyTypeIsInvalid() {
        var candidate = new MemoryCandidate("", "some content", 0.8, List.of("entry-1"));
        assertFalse(candidate.isValid());
    }

    @Test
    void emptyContentIsInvalid() {
        var candidate = new MemoryCandidate("fact", "", 0.8, List.of("entry-1"));
        assertFalse(candidate.isValid());
    }

    @Test
    void zeroConfidenceIsInvalid() {
        var candidate = new MemoryCandidate("fact", "some content", 0.0, List.of("entry-1"));
        assertFalse(candidate.isValid());
    }

    @Test
    void noCitationsIsInvalid() {
        var candidate = new MemoryCandidate("fact", "some content", 0.8, List.of());
        assertFalse(candidate.isValid());
    }

    @Test
    void factoryNormalizesNulls() {
        var candidate = MemoryCandidate.create(null, null, 0.5, null);
        assertEquals("", candidate.type());
        assertEquals("", candidate.content());
        assertEquals(List.of(), candidate.citations());
        assertFalse(candidate.isValid());
    }

    @Test
    void compactConstructorNormalizesNulls() {
        var candidate = new MemoryCandidate(null, null, 0.5, null);
        assertEquals("", candidate.type());
        assertEquals("", candidate.content());
        assertEquals(List.of(), candidate.citations());
    }
}
