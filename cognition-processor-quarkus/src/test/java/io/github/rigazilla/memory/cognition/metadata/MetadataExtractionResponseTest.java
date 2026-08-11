package io.github.rigazilla.memory.cognition.metadata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExtractedEntity} and {@link MetadataExtractionResponse} records.
 *
 * <p>Both are plain data records with compact constructors that coerce null lists to
 * empty lists. Tests cover construction, field accessors, null-safety, and record
 * equality / hashCode.
 */
class MetadataExtractionResponseTest {

    // -------------------------------------------------------------------------
    // ExtractedEntity
    // -------------------------------------------------------------------------

    @Test
    void extractedEntityStoresNameAndType() {
        ExtractedEntity entity = new ExtractedEntity("Python", "technology");
        assertEquals("Python", entity.name());
        assertEquals("technology", entity.type());
    }

    @Test
    void extractedEntityRecordEquality() {
        ExtractedEntity a = new ExtractedEntity("AWS", "technology");
        ExtractedEntity b = new ExtractedEntity("AWS", "technology");
        ExtractedEntity c = new ExtractedEntity("AWS", "organization");

        assertEquals(a, b, "identical entities must be equal");
        assertEquals(a.hashCode(), b.hashCode(), "equal entities must share the same hashCode");
        assertNotEquals(a, c, "entities differing in type must not be equal");
    }

    @Test
    void extractedEntityAllowsNullFields() {
        // Records do not enforce non-null unless the compact constructor does so;
        // ExtractedEntity has no such enforcement — callers are responsible.
        ExtractedEntity entity = new ExtractedEntity(null, null);
        assertNull(entity.name());
        assertNull(entity.type());
    }

    @Test
    void extractedEntityAllValidTypes() {
        // Smoke-test that all six valid type strings are accepted without issue
        for (String type : List.of("technology", "organization", "person",
                "location", "product", "concept")) {
            ExtractedEntity e = new ExtractedEntity("SomeEntity", type);
            assertEquals(type, e.type());
        }
    }

    // -------------------------------------------------------------------------
    // MetadataExtractionResponse — null-safety in compact constructor
    // -------------------------------------------------------------------------

    @Test
    void nullEntitiesAreReplacedWithEmptyList() {
        MetadataExtractionResponse resp = new MetadataExtractionResponse(null, List.of("topic"));
        assertNotNull(resp.entities(), "null entities must be replaced with an empty list");
        assertTrue(resp.entities().isEmpty());
    }

    @Test
    void nullTopicsAreReplacedWithEmptyList() {
        MetadataExtractionResponse resp = new MetadataExtractionResponse(List.of(), null);
        assertNotNull(resp.topics(), "null topics must be replaced with an empty list");
        assertTrue(resp.topics().isEmpty());
    }

    @Test
    void bothNullFieldsAreReplacedWithEmptyLists() {
        MetadataExtractionResponse resp = new MetadataExtractionResponse(null, null);
        assertNotNull(resp.entities());
        assertNotNull(resp.topics());
        assertTrue(resp.entities().isEmpty());
        assertTrue(resp.topics().isEmpty());
    }

    @Test
    void populatedResponseRetainsContents() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("Python", "technology"),
                new ExtractedEntity("AWS", "technology"));
        List<String> topics = List.of("programming/scripting", "cloud/aws");

        MetadataExtractionResponse resp = new MetadataExtractionResponse(entities, topics);

        assertEquals(2, resp.entities().size());
        assertEquals(2, resp.topics().size());
        assertEquals("Python", resp.entities().get(0).name());
        assertEquals("cloud/aws", resp.topics().get(1));
    }

    @Test
    void responseRecordEquality() {
        List<ExtractedEntity> entities = List.of(new ExtractedEntity("Alice", "person"));
        List<String> topics = List.of("people");

        MetadataExtractionResponse a = new MetadataExtractionResponse(entities, topics);
        MetadataExtractionResponse b = new MetadataExtractionResponse(entities, topics);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void mutableListPassedInIsWrapped() {
        // Compact constructor assigns the non-null list as-is; the record API guarantees
        // the reference is preserved. This test documents that behaviour rather than
        // asserting immutability (which is not guaranteed by the record design).
        List<String> mutableTopics = new ArrayList<>();
        mutableTopics.add("programming");
        MetadataExtractionResponse resp = new MetadataExtractionResponse(List.of(), mutableTopics);
        assertEquals(1, resp.topics().size());
    }
}
