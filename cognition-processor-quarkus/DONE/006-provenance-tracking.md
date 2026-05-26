# 006: Provenance Tracking with Denormalized Entry IDs

**Status**: ✅ Complete  
**Date**: 2026-05-25

## Overview

Implemented comprehensive provenance tracking for extracted memories, following Enhancement 099 specification. Each memory now contains a self-contained record of:
- Which conversation it came from
- Which specific batch of entries was processed
- When and why the batch was triggered
- Which runtime version performed the extraction

This enables **audit, replay, and debugging** of memory extraction.

## Problem Solved

**Before:** No way to answer "Which evidence pack produced this memory?"
- Memories had no link back to conversation
- No record of which entries were included
- No batch or processing metadata
- Impossible to replay or debug extractions

**After:** Full provenance tracking
- Each memory contains conversation ID
- Denormalized list of entry IDs from the batch
- Event cursor range and batch trigger reason
- Runtime attribution and timestamp
- Self-contained and replay-friendly

## Architecture Decision: Denormalized Entry IDs

Chose **Option 2** (denormalized entry IDs) over persisting ScopeJob IDs:

### Why Denormalization?
1. **Self-contained** - All provenance in one place, no auxiliary lookups
2. **Spec-aligned** - Enhancement 099 shows `provenance.entry_ids`, not `batch_id`
3. **ScopeJob is ephemeral** - It's an implementation detail that may change
4. **Simpler architecture** - No need to persist ScopeJob history
5. **Replay-friendly** - Can reconstruct evidence from entry IDs alone
6. **Future-proof** - Works even if batching strategy changes

### Storage Cost Analysis
```
Denormalized: 20 entries × 36 bytes = 720 bytes
Normalized:   1 job ID × 36 bytes = 36 bytes
Difference:   ~700 bytes per memory
```
Negligible compared to memory content itself (100s-1000s bytes).

## Components Implemented

### 1. Provenance Model (`Provenance.java`)

```java
public record Provenance(
    // Batch identification
    String conversationId,
    List<String> entryIds,        // Denormalized from ScopeJob
    String firstEventCursor,
    String latestEventCursor,
    String batchTrigger,          // Why promoted: debounce_delay, max_batch_age, etc.
    
    // Evidence pack fingerprint (Phase 3A: not yet implemented)
    String sourceHash,            // SHA-256 of evidence pack
    String evidenceBaseId,        // Compacted base ID (when implemented)
    String evidenceBaseHash,      // Hash of compacted base
    
    // Runtime attribution
    String runtimeId,
    String runtimeVersion,
    Instant processedAt
)
```

**Factory methods:**
- `fromScopeJob()` - Full provenance (when evidence hashing is implemented)
- `fromScopeJobMinimal()` - Phase 3A version (hash fields are null)

### 2. Updated MemoryWriter

**Method signatures:**
```java
// Single memory
MemoryWriteResult writeMemory(String userId, MemoryCandidate candidate, Provenance provenance)

// Batch (all memories share same provenance)
List<MemoryWriteResult> writeMemories(String userId, List<MemoryCandidate> candidates, Provenance provenance)
```

**Provenance serialization:**
```java
{
  "content": "I work at Acme Corp",
  "confidence": 0.95,
  "citations": ["[USER] I work at..."],
  
  "provenance": {
    "conversation_id": "conv-abc-123",
    "entry_ids": ["entry-1", "entry-2", "entry-3"],
    "event_cursors": {
      "first": "100",
      "latest": "102"
    },
    "batch_trigger": "DEBOUNCE_DELAY",
    "runtime_id": "cognition-processor-v1",
    "runtime_version": "1.0.0-SNAPSHOT",
    "processed_at": "2026-05-25T14:30:00Z"
  }
}
```

### 3. Updated EvidencePack

Added placeholder methods for future implementation:
- `computeHash()` - Will compute SHA-256 of canonicalized evidence
- `getEvidenceBaseId()` - Will return compacted base ID
- `getEvidenceBaseHash()` - Will return compacted base hash

Currently return `null` (Phase 3A).

### 4. Updated JobProcessor

**Pipeline flow:**
```java
// Stage 0: Load conversation metadata
String userId = getConversationOwner(job.conversationId());

// Stage 1: Load evidence
EvidencePack evidence = transcriptLoader.loadTranscript(job.conversationId());

// Build provenance from ScopeJob
Provenance provenance = Provenance.fromScopeJobMinimal(job, runtimeId, runtimeVersion);

// Stage 2: Extract memories
DurableExtractionResponse extraction = extractor.extract(evidenceText);

// Stage 3: Verify memories
DurableVerificationResponse verification = verifier.verify(candidatesJson, evidenceText);

// Stage 4: Write memories WITH PROVENANCE
memoryWriter.writeMemories(userId, verification.verified(), provenance);
```

**Added config properties:**
```properties
cognition.runtime.id=cognition-processor-v1
cognition.runtime.version=1.0.0-SNAPSHOT
```

## Usage Examples

### Query memories from a specific conversation

```bash
curl -s "http://localhost:8082/v1/memories?namespace=user&namespace=*&namespace=cognition.v1&namespace=*" \
  -H "X-API-Key: admin-api-key-1" \
  -H "Authorization: Bearer admin-api-key-1" | \
  jq '.memories[] | select(.value.provenance.conversation_id == "abc-123")'
```

### Query memories from a specific batch

```bash
curl -s "http://localhost:8082/v1/memories?..." | \
  jq '.memories[] | select(.value.provenance.entry_ids | contains(["entry-xyz"]))'
```

### Find memories by batch trigger

```bash
curl -s "http://localhost:8082/v1/memories?..." | \
  jq '.memories[] | select(.value.provenance.batch_trigger == "DEBOUNCE_DELAY")'
```

### Find memories by runtime version

```bash
curl -s "http://localhost:8082/v1/memories?..." | \
  jq '.memories[] | select(.value.provenance.runtime_version == "1.0.0-SNAPSHOT")'
```

### Reconstruct evidence pack (for replay/audit)

```python
# 1. Get memory with provenance
memory = get_memory(memory_id)
provenance = memory['provenance']

# 2. Load the specific entries from the batch
entry_ids = provenance['entry_ids']
entries = [memory_service.get_entry(eid) for eid in entry_ids]

# 3. When source_hash is implemented:
evidence_pack = EvidencePack(entries)
assert evidence_pack.compute_hash() == provenance['source_hash']

# 4. Replay extraction
extraction = extractor.extract(evidence_pack)
```

## What's Still Missing (Phase 3A Limitations)

### 1. Evidence Pack Hashing ❌
- `provenance.source_hash` is always `null`
- Cannot detect duplicate processing
- Cannot verify evidence pack integrity

**Future:** Implement SHA-256 hashing of canonicalized evidence in `EvidencePack.computeHash()`

### 2. Evidence Base Tracking ❌
- `provenance.evidence_base_id` is always `null`
- `provenance.evidence_base_hash` is always `null`
- No compacted evidence bases yet

**Future:** Implement compaction per Enhancement 099, store bases in cognition cache

### 3. Batch Entry Filtering ✅ (Resolved in 011)
- ~~`TranscriptLoader.loadTranscript()` still loads ENTIRE conversation~~ ✅ Now filters server-side
- ~~`job.entryIds()` is not used to filter~~ ✅ Now used for range boundaries
- ~~Evidence pack contains more than the batch~~ ✅ Evidence pack = batch exactly

**Resolved:** See `DONE/011-window-linking-server-side-filtering.md` for implementation details

### 4. Memory ID References ❌
- `provenance.memory_ids` not tracked
- Cannot link to related derived memories

**Future:** Add when consolidation is implemented

## Files Created

- `src/main/java/io/github/rigazilla/memory/cognition/model/Provenance.java`

## Files Modified

- `src/main/java/io/github/rigazilla/memory/cognition/writer/MemoryWriter.java`
  - Added `Provenance` parameter to write methods
  - Added `buildProvenanceValue()` serialization
  - Added helper methods for protobuf conversion

- `src/main/java/io/github/rigazilla/memory/cognition/evidence/EvidencePack.java`
  - Added `computeHash()` (placeholder)
  - Added `getEvidenceBaseId()` (placeholder)
  - Added `getEvidenceBaseHash()` (placeholder)

- `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`
  - Added `Provenance` import
  - Added `runtimeId` and `runtimeVersion` config properties
  - Build provenance from ScopeJob in pipeline
  - Pass provenance to memory writer

- `src/main/resources/application.properties`
  - Added `cognition.runtime.version=1.0.0-SNAPSHOT`

## Bug Fix: Entry ID Extraction

**Issue discovered during testing:** Entry IDs were not being extracted from events, resulting in empty `entry_ids: []` arrays in provenance.

**Root cause:** Event JSON uses field name `"entry"`, not `"entry_id"` or `"id"`.

Example entry event data:
```json
{
  "conversation": "edb4e6b1-4b18-4eb3-8c52-d8b97509b1fc",
  "entry": "b21cfb33-8940-41cc-bca8-22a4f9bcd3f0",  // ← Actual field name
  "entry_channel": "history",
  "entry_content_type": "history/lc4j",
  "entry_role": "AI"
}
```

**Fix:** Updated `GrpcAdminEventClient.handleEvent()` to extract `"entry"` field first:
```java
// Try "entry" first (actual field name in entry events)
entryId = extractJsonField(jsonData, "entry");
if (entryId == null) {
    entryId = extractJsonField(jsonData, "entry_id");
}
if (entryId == null) {
    entryId = extractJsonField(jsonData, "id");
}
```

**Result:** Entry IDs now correctly populate `provenance.entry_ids` array.

## Build & Test

### Compilation
```bash
mvn compile
# ✅ SUCCESS - All files compile without errors
```

### Manual Testing
```bash
# Start cognition processor
mvn quarkus:dev

# Create conversation with entries
# (see DONE/003-job-processing-pipeline.md for test commands)

# After processing, query memories and verify provenance is present
curl -s "http://localhost:8082/v1/memories?..." | \
  jq '.memories[0].value.provenance'
```

Expected output:
```json
{
  "conversation_id": "abc-123",
  "entry_ids": ["entry-1", "entry-2", "entry-3"],
  "event_cursors": {
    "first": "100",
    "latest": "102"
  },
  "batch_trigger": "DEBOUNCE_DELAY",
  "runtime_id": "cognition-processor-v1",
  "runtime_version": "1.0.0-SNAPSHOT",
  "processed_at": "2026-05-25T14:30:00.123Z"
}
```

## Benefits

### 1. Audit Trail ✅
- Know exactly which entries contributed to each memory
- Trace back to original conversation
- Identify which runtime version extracted

### 2. Debugging ✅
- When extraction produces bad memories, can inspect exact evidence
- Can correlate memories with event cursors in admin stream
- Can filter by batch trigger to understand promotion patterns

### 3. Replay Capability ✅
- Entry IDs allow reconstructing evidence pack
- Can re-run extraction on same evidence
- Can compare results across runtime versions

### 4. Analytics ✅
- Query memories by conversation
- Analyze batch trigger patterns
- Compare extraction quality across versions

### 5. Compliance ✅
- Satisfies "where did this data come from" requirements
- Enables data lineage tracking
- Supports GDPR/privacy deletion (by conversation)

## Next Steps

### High Priority
1. **Implement evidence pack hashing** - Enable duplicate detection
2. **Filter evidence by entry IDs** - Only load batch entries, not entire conversation
3. **Add integration test** - Verify provenance is persisted correctly

### Medium Priority
4. **Implement compacted evidence bases** - Reduce LLM context usage
5. **Add debug evidence persistence** - Opt-in evidence pack dumps for troubleshooting

### Low Priority
6. **Track related memory IDs** - Link to episodic/derived memories (when consolidation exists)

## References

- **Enhancement 099**: Quarkus + LangChain4j Cognition Processor
  - Section: Evidence Pack Builder
  - Provenance Tracking specification
- **Phase 3**: Job Processing Pipeline (`DONE/003-job-processing-pipeline.md`)
- **ScopeJob**: Batch structure (`src/main/java/io/github/rigazilla/memory/cognition/event/ScopeJob.java`)
