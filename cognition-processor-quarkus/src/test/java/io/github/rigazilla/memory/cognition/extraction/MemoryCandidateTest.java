package io.github.rigazilla.memory.cognition.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MemoryCandidate record.
 * Tests validation, factory methods, and edge cases for extracted memories.
 */
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

    @Test
    void testValidCandidate_allMemoryTypes() {
        // Test all valid memory types
        var fact = new MemoryCandidate("fact", "User is 25 years old", 0.95, List.of("entry-1"));
        var preference = new MemoryCandidate("preference", "Prefers email over phone", 0.85, List.of("entry-2"));
        var procedure = new MemoryCandidate("procedure", "Always check logs first", 0.90, List.of("entry-3"));
        var solution = new MemoryCandidate("problem_solution", "Fixed by restarting service", 0.88, List.of("entry-4"));
        var decision = new MemoryCandidate("decision", "Chose React over Vue", 0.92, List.of("entry-5"));

        assertThat(fact.isValid()).isTrue();
        assertThat(preference.isValid()).isTrue();
        assertThat(procedure.isValid()).isTrue();
        assertThat(solution.isValid()).isTrue();
        assertThat(decision.isValid()).isTrue();
    }

    @Test
    void testConfidence_boundaryValues() {
        // Test minimum valid confidence (just above 0.0)
        var minValid = new MemoryCandidate("fact", "content", 0.01, List.of("entry-1"));
        assertThat(minValid.isValid()).isTrue();

        // Test maximum confidence
        var maxValid = new MemoryCandidate("fact", "content", 1.0, List.of("entry-1"));
        assertThat(maxValid.isValid()).isTrue();

        // Test exactly 0.0 is invalid
        var zeroInvalid = new MemoryCandidate("fact", "content", 0.0, List.of("entry-1"));
        assertThat(zeroInvalid.isValid()).isFalse();

        // Test negative confidence is invalid
        var negativeInvalid = new MemoryCandidate("fact", "content", -0.1, List.of("entry-1"));
        assertThat(negativeInvalid.isValid()).isFalse();
    }

    @Test
    void testCitations_multipleCitations() {
        // Test with multiple citations
        var candidate = new MemoryCandidate(
            "fact",
            "User mentioned this multiple times",
            0.95,
            List.of("entry-1", "entry-2", "entry-3", "entry-4")
        );

        assertThat(candidate.isValid()).isTrue();
        assertThat(candidate.citations()).hasSize(4);
        assertThat(candidate.citations()).containsExactly("entry-1", "entry-2", "entry-3", "entry-4");
    }

    @Test
    void testContent_longContent() {
        // Test with very long content
        String longContent = "This is a very long memory content that exceeds 50 characters and should be truncated in toString output for readability purposes";
        var candidate = new MemoryCandidate("fact", longContent, 0.9, List.of("entry-1"));

        assertThat(candidate.isValid()).isTrue();
        assertThat(candidate.content()).isEqualTo(longContent);
        assertThat(candidate.toString()).contains("...");  // Should be truncated in toString
    }

    @Test
    void testContent_shortContent() {
        // Test with short content (not truncated in toString)
        String shortContent = "Short memory";
        var candidate = new MemoryCandidate("fact", shortContent, 0.9, List.of("entry-1"));

        assertThat(candidate.isValid()).isTrue();
        assertThat(candidate.toString()).contains(shortContent);
        assertThat(candidate.toString()).doesNotContain("...");
    }

    @Test
    void testToString_containsAllFields() {
        var candidate = new MemoryCandidate("preference", "User likes coffee", 0.87, List.of("entry-1", "entry-2"));
        String result = candidate.toString();

        assertThat(result).contains("MemoryCandidate");
        assertThat(result).contains("type=preference");
        assertThat(result).contains("confidence=0.87");
        assertThat(result).contains("citations=2");
        assertThat(result).contains("User likes coffee");
        assertThat(result).contains("valid=true");
    }

    @Test
    void testToString_invalidCandidate() {
        var candidate = new MemoryCandidate("", "", 0.0, List.of());
        String result = candidate.toString();

        assertThat(result).contains("valid=false");
    }

    @Test
    void testBlankType_isInvalid() {
        // Test that whitespace-only type is invalid
        var candidate = new MemoryCandidate("   ", "content", 0.8, List.of("entry-1"));
        assertThat(candidate.isValid()).isFalse();
    }

    @Test
    void testBlankContent_isInvalid() {
        // Test that whitespace-only content is invalid
        var candidate = new MemoryCandidate("fact", "   ", 0.8, List.of("entry-1"));
        assertThat(candidate.isValid()).isFalse();
    }

    @Test
    void testRecordEquality() {
        var candidate1 = new MemoryCandidate("fact", "User is 30", 0.9, List.of("entry-1"));
        var candidate2 = new MemoryCandidate("fact", "User is 30", 0.9, List.of("entry-1"));
        var candidate3 = new MemoryCandidate("fact", "User is 31", 0.9, List.of("entry-1"));

        assertThat(candidate1).isEqualTo(candidate2);
        assertThat(candidate1.hashCode()).isEqualTo(candidate2.hashCode());
        assertThat(candidate1).isNotEqualTo(candidate3);
    }
}
