package io.github.rigazilla.memory.cognition.consolidation;

import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Merges a new {@link MemoryCandidate} with its duplicate {@link MemoryItem}s
 * already stored in memory-service.
 * <p>
 * Merge rules:
 * <ul>
 *   <li>Keep the highest confidence score across the new candidate and the duplicate.</li>
 *   <li>Combine (union) citations from all versions, preserving insertion order.</li>
 *   <li>Carry the duplicate's {@code key} and {@code revision} so the writer can
 *       perform a guarded upsert instead of a blind insert.</li>
 * </ul>
 */
@ApplicationScoped
public class MemoryMerger {

    private static final Logger LOG = Logger.getLogger(MemoryMerger.class);

    private static final Value ZERO_CONFIDENCE =
            Value.newBuilder().setNumberValue(0.0).build();
    private static final Value EMPTY_CITATIONS =
            Value.newBuilder().setListValue(ListValue.newBuilder().build()).build();

    /** Matches the leading "E<digits>: " prefix (e.g. "E1: ", "E12: ") that MemoryWriter strips before storage. */
    private static final Pattern ENTRY_REF_PREFIX = Pattern.compile("^E(\\d+):\\s*");

    /**
     * Merge {@code newCandidate} with the best matching existing {@link MemoryItem}.
     * <p>
     * When multiple duplicates are present the one with the highest revision is
     * chosen as the update target (it is the most recent authoritative entry).
     *
     * @param newCandidate the freshly extracted candidate
     * @param duplicates   non-empty list of existing matching memory items
     * @return a {@link ResolvedCandidate} carrying the merged data and the
     *         existing entry's key + revision for a guarded upsert
     */
    public ResolvedCandidate merge(MemoryCandidate newCandidate, List<MemoryItem> duplicates) {
        // Pick the existing item with the highest revision as the upsert target.
        // Secondary sort by key ensures deterministic selection when two items share
        // the same revision (e.g. two independently-keyed entries both at revision 1).
        MemoryItem target = duplicates.stream()
                .max(Comparator.comparingLong(MemoryItem::getRevision)
                        .thenComparing(MemoryItem::getKey))
                .orElseThrow(() -> new NoSuchElementException("duplicates list must not be empty"));

        // Highest confidence across new candidate and existing entry
        double existingConfidence = target.getValue()
                .getFieldsOrDefault("confidence", ZERO_CONFIDENCE)
                .getNumberValue();
        double mergedConfidence = Math.max(newCandidate.confidence(), existingConfidence);

        // Union citations: new candidate first, then existing — deduplicated, insertion-order preserved.
        // Citations are canonicalised (E<n>: prefix stripped) before the union so that a raw citation
        // from the new candidate ("E1: foo") and its already-stripped counterpart from storage ("foo")
        // are recognised as the same string and do not both survive into the merged list.
        Set<String> seen = new LinkedHashSet<>();
        for (String c : newCandidate.citations()) {
            seen.add(stripEntryRefPrefix(c));
        }
        ListValue existingCitations = target.getValue()
                .getFieldsOrDefault("citations", EMPTY_CITATIONS)
                .getListValue();
        for (Value v : existingCitations.getValuesList()) {
            seen.add(v.getStringValue()); // already stripped — stored by MemoryWriter
        }
        List<String> mergedCitations = new ArrayList<>(seen);

        MemoryCandidate merged = new MemoryCandidate(
                newCandidate.type(),
                newCandidate.content(),
                mergedConfidence,
                mergedCitations
        );

        LOG.debugf("Merged candidate [%s]: confidence %.2f→%.2f, citations %d+%d→%d, key=%s rev=%d",
                newCandidate.type(),
                newCandidate.confidence(), mergedConfidence,
                newCandidate.citations().size(), existingCitations.getValuesCount(),
                mergedCitations.size(),
                target.getKey(), target.getRevision());

        return ResolvedCandidate.merged(merged, target.getKey(), target.getRevision());
    }

    /**
     * Strip leading "E<digits>: " entry-reference prefix (e.g. "E1: ", "E12: ") from a citation
     * string, returning the same form that
     * {@link io.github.rigazilla.memory.cognition.writer.MemoryWriter} stores.
     * Canonicalising here prevents the raw and stripped forms from both surviving
     * the union on re-merge.
     */
    static String stripEntryRefPrefix(String citation) {
        if (citation == null) {
            return null;
        }
        return ENTRY_REF_PREFIX.matcher(citation).replaceFirst("");
    }
}
