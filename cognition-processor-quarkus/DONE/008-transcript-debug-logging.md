# 008: Transcript Debug Logging

**Status**: ✅ Complete  
**Date**: 2026-05-25

## Overview

Added comprehensive DEBUG-level logging to show transcript loading and evidence formatting. This provides complete visibility into what evidence is being loaded and sent to the LLM extractor, helping verify the pipeline is working correctly.

**Log Level**: DEBUG - Requires `quarkus.log.category."io.github.rigazilla.memory".level=DEBUG`

## Problem Solved

**Before:**
- No visibility into transcript content being loaded
- Couldn't verify evidence was correct
- Difficult to debug extraction issues
- Unknown what text the LLM was actually seeing

**After:**
- See each entry loaded with ID, role, and content preview
- See formatted evidence text sent to LLM
- Verify transcript loading is working
- Debug evidence formatting issues

## Implementation

### 1. TranscriptLoader - Entry-Level Logging

Added logging for each transcript entry loaded:

```java
// Debug log: show entry details
if (LOG.isDebugEnabled() && !entries.isEmpty()) {
    LOG.debugf("Transcript entries for conversation %s:", conversationId);
    for (Entry entry : entries) {
        logEntryDetails(entry);
    }
}

/**
 * Log entry details for debugging.
 */
private void logEntryDetails(Entry entry) {
    try {
        String entryId = entry.getId().isEmpty() ? "(no-id)" : bytesToUuid(entry.getId());
        String contentType = entry.getContentType();

        // Extract role and text from history content
        if ("history".equals(contentType) && entry.getContentCount() > 0) {
            var content = entry.getContent(0);
            if (content.hasStructValue()) {
                var struct = content.getStructValue();
                String role = struct.getFieldsOrDefault("role",
                    Value.newBuilder().setStringValue("UNKNOWN").build())
                    .getStringValue();
                String text = struct.getFieldsOrDefault("text",
                    Value.newBuilder().setStringValue("").build())
                    .getStringValue();

                String preview = text.length() > 100 ? text.substring(0, 97) + "..." : text;
                LOG.debugf("  - Entry %s [%s]: %s", entryId, role, preview);
            } else {
                LOG.debugf("  - Entry %s [%s]: (non-struct content)", entryId, contentType);
            }
        } else {
            LOG.debugf("  - Entry %s [%s]: (non-history content)", entryId, contentType);
        }
    } catch (Exception e) {
        LOG.debugf("  - Entry (error logging details): %s", e.getMessage());
    }
}
```

### 2. JobProcessor - Evidence Text Logging

Added logging for formatted evidence sent to LLM:

```java
// Stage 2: Extract Memories
LOG.infof("  [2/5] Extracting memories from evidence");
String evidenceText = evidence.formatAsText();

// Debug log: show formatted evidence sent to LLM
if (LOG.isDebugEnabled()) {
    LOG.debugf("  Evidence text sent to extractor (%d chars):", evidenceText.length());
    String preview = evidenceText.length() > 500
        ? evidenceText.substring(0, 497) + "..."
        : evidenceText;
    LOG.debugf("  %s", preview);
    if (evidenceText.length() > 500) {
        LOG.debugf("  ... (truncated, full length: %d chars)", evidenceText.length());
    }
}

DurableExtractionResponse extraction = extractor.extract(evidenceText);
```

## Example Log Output

**Note**: Requires DEBUG level enabled in `application.properties`.

### Transcript Entry Logging

```
2026-05-25 18:00:15 INFO  [TranscriptLoader] Loaded 4 transcript entries for conversation abc-123
2026-05-25 18:00:15 DEBUG [TranscriptLoader] Transcript entries for conversation abc-123:
2026-05-25 18:00:15 DEBUG [TranscriptLoader]   - Entry 581b6f1d-b04d-4695-9359-2557b082e163 [USER]: I prefer crunchy crust over tender
2026-05-25 18:00:15 DEBUG [TranscriptLoader]   - Entry ecc6b7dc-824b-45b9-b83e-c1ed5a2faf16 [AI]: I'll remember that you prefer crunchy crust pizza.
2026-05-25 18:00:15 DEBUG [TranscriptLoader]   - Entry 5428811c-50d0-4197-85f6-89d47b447183 [USER]: Also I have a degree in physics
2026-05-25 18:00:15 DEBUG [TranscriptLoader]   - Entry 7fe0db63-9e48-4943-bcba-21f65c3b97ad [AI]: Got it, you have a background in physics.
```

### Evidence Text Logging

```
2026-05-25 18:00:16 INFO  [JobProcessor]   [2/5] Extracting memories from evidence
2026-05-25 18:00:16 DEBUG [JobProcessor]   Evidence text sent to extractor (892 chars):
2026-05-25 18:00:16 DEBUG [JobProcessor]   === CONVERSATION TRANSCRIPT ===

[USER] I prefer crunchy crust over tender

[AI] I'll remember that you prefer crunchy crust pizza.

[USER] Also I have a degree in physics

[AI] Got it, you have a background in physics.

2026-05-25 18:00:16 DEBUG [JobProcessor]   ... (truncated, full length: 892 chars)
```

## What Gets Logged

### Entry-Level Details (TranscriptLoader)

For each entry:
- **Entry ID**: UUID of the entry
- **Role**: USER or AI
- **Content preview**: First 100 characters of text
- **Content type**: "history" or other format
- **Special cases**: Non-struct or non-history entries logged differently

### Evidence Text (JobProcessor)

- **Total length**: Character count of formatted evidence
- **Content preview**: First 500 characters
- **Full text indicator**: Shows if truncated with total length

## Benefits

### 1. Verify Transcript Loading ✅
- See exactly which entries are loaded
- Verify entry IDs match expected batch
- Confirm role and content are correct

### 2. Debug Evidence Formatting ✅
- See exact text sent to LLM
- Verify formatting is correct (role labels, separators)
- Identify malformed entries

### 3. Trace End-to-End ✅
- Connect event → entry → evidence → extraction
- Follow provenance chain
- Verify nothing is lost

### 4. Debug Extraction Issues ✅
- When LLM produces bad memories, see input
- Verify evidence quality
- Identify prompt engineering needs

## Usage

### Enable DEBUG Logging

Ensure DEBUG level is enabled (already in your config):
```properties
quarkus.log.category."io.github.rigazilla.memory".level=DEBUG
```

### Monitor Transcripts

```bash
# View transcript loading
grep "Transcript entries" logs/quarkus.log

# See entry details
grep "Entry.*\[USER\]\|\[AI\]" logs/quarkus.log

# Check evidence sent to extractor
grep "Evidence text sent" logs/quarkus.log -A 20

# Count entries per conversation
grep "Loaded.*transcript entries" logs/quarkus.log | \
  awk '{print $6}' | sort | uniq -c
```

### Verify Pipeline

```bash
# Follow a specific conversation through pipeline
CONV_ID="abc-123"
grep "$CONV_ID" logs/quarkus.log | grep -E "Loaded|Entry|Evidence"

# Check if entries match provenance
# Compare entry IDs in transcript vs. provenance.entry_ids
```

## Troubleshooting Scenarios

### Issue: No memories extracted

**Check transcript logging:**
1. Are entries being loaded? (Should see "Loaded N entries")
2. Is evidence text empty? (Check "Evidence text sent")
3. Are entries formatted correctly? (Check role/content)

### Issue: Wrong memories extracted

**Check evidence formatting:**
1. Does evidence text match expected conversation?
2. Are roles labeled correctly ([USER] vs [AI])?
3. Is content truncated or malformed?

### Issue: Duplicate memories

**Check batch boundaries:**
1. Which entry IDs are in each batch?
2. Do batches overlap in content?
3. Is same evidence being reprocessed?

## Performance Considerations

### Log Volume

With DEBUG enabled and active conversations:
- **~10 lines per entry** (entry details)
- **~10 lines per job** (evidence text)
- **~100 lines per batch** (for 4 entries)

**Recommendation**: Use DEBUG only when troubleshooting. Set to INFO in production.

### Text Truncation

Evidence text is truncated to 500 chars in logs to:
- Avoid log bloat
- Keep logs readable
- Still show meaningful preview

**Full text** is sent to LLM (not truncated), only the log preview is truncated.

## Files Modified

- `src/main/java/io/github/rigazilla/memory/cognition/evidence/TranscriptLoader.java`
  - Added `logEntryDetails()` method
  - Added `bytesToUuid()` helper method
  - Added entry logging after loading transcript

- `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`
  - Added evidence text logging before extraction
  - Shows character count and preview

## Testing

### Compilation
```bash
mvn compile
# ✅ SUCCESS
```

### Expected Behavior

With DEBUG enabled:
1. **For each job**, see transcript entry details
2. **Before extraction**, see evidence text preview
3. **Entry IDs match** provenance.entry_ids in final memories
4. **Content matches** what was in conversation

### What to Verify

**Normal operation:**
- Entry IDs are valid UUIDs
- Roles are "USER" or "AI"
- Content is readable text
- Evidence format is consistent

**Warning signs:**
- "(no-id)" entries → Entry UUID missing
- "(non-struct content)" → Unexpected entry format
- Empty evidence text → No entries loaded
- Malformed text → Encoding or parsing issue

## Next Steps

### Optional Enhancements

1. **Add entry metadata logging**
   - Show epoch, created_at timestamps
   - Show content_type variations
   - Log channel information

2. **Add diff highlighting**
   - Show what changed between batches
   - Highlight new entries
   - Mark repeated content

3. **Structured logging**
   - Output as JSON for parsing
   - Enable log aggregation
   - Support ELK/Splunk integration

4. **Sampling for production**
   - Log every Nth job
   - Sample by conversation
   - Configurable sampling rate

## References

- **Phase 3**: Job Processing Pipeline (`DONE/003-job-processing-pipeline.md`)
- **Provenance Tracking**: Entry IDs in provenance (`DONE/006-provenance-tracking.md`)
- **Evidence Pack**: Formatting logic (`src/.../evidence/EvidencePack.java`)
