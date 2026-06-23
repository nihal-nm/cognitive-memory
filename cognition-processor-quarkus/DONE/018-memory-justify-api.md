# 018 - Memory Justify API

## Summary

Implemented a REST API endpoint that provides full justification for why a cognitive memory entry was created. The endpoint expands the `entry_ids` from the memory's provenance into actual conversation entry content, allowing users to see the complete context that led to memory extraction.

## Motivation

Users need to understand why the cognition processor created specific memory entries. While memories already contain:
- `citations` - Quotes from the conversation
- `confidence` - Extraction confidence score
- `provenance.entry_ids` - List of entry UUIDs that were processed

The entry IDs alone are not human-readable. Users need to see the actual conversation content to understand the full context and reasoning behind memory creation.

## Solution

### API Endpoint

**GET** `/api/memories/{memoryId}/justify`

Returns a memory with its provenance entry IDs expanded into simplified, user-friendly details:
- Memory content, confidence, and citations
- Conversation ID (source conversation)
- Source entries with just role, text, and timestamp

### Response Structure

Simplified response with no technical metadata:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "content": "User prefers warm climates for travel destinations",
  "confidence": 0.95,
  "citations": [
    "I love warm weather",
    "I'd like to visit somewhere tropical"
  ],
  "conversation_id": "05278fdf-78e4-4c3e-a39d-053450f222c4",
  "source_entries": [
    {
      "role": "USER",
      "text": "I'd like to visit a country near the arctic circle",
      "created_at": "2026-06-23T07:58:25.231163Z"
    },
    {
      "role": "AI",
      "text": "Great! Where do you want your next travel destination to be?",
      "created_at": "2026-06-23T07:57:36.675439Z"
    }
  ],
  "created_at": "2026-06-23T08:15:23.016326848Z"
}
```

**Technical metadata filtered out** (but preserved in storage):
- namespace, key
- provenance details (event_cursors, batch_trigger, source_hash, runtime_id, etc.)
- entry metadata (id, conversation_id, user_id, channel, epoch, content_type)

### Architecture

#### Components Created

1. **MemoryJustifyResponse** - Simplified response DTO containing:
   - Memory: id, content, confidence, citations, created_at
   - Conversation ID
   - Source entries: role, text, created_at

2. **MemoryJustifyService** - Business logic service that:
   - Fetches memory from memory-service via `AdminMemoriesService.GetMemory`
   - Extracts provenance and entry IDs from memory value
   - Fetches each entry via `AdminEntriesService.GetEntry`
   - Handles both USER entries (simple text field) and AI entries (complex events structure)
   - Handles missing/archived entries gracefully with placeholders
   - Converts protobuf structures to simplified JSON DTOs

3. **MemoryJustifyResource** - REST endpoint that:
   - Exposes GET `/api/memories/{memoryId}/justify`
   - Returns 200 OK with simplified justification
   - Returns 404 Not Found if memory doesn't exist
   - Returns 500 Internal Server Error on failures

### Implementation Details

#### gRPC Integration

The service uses two gRPC admin APIs:

1. **AdminMemoriesService.GetMemory** - Retrieves the memory by ID
   - Requires admin role (cognition processor has this)
   - Returns full memory with value struct containing provenance

2. **AdminEntriesService.GetEntry** - Retrieves individual entries by ID
   - Requires admin or auditor role
   - Can retrieve entries from archived conversations
   - Returns entry with content, role, timestamps

#### Authentication

Both gRPC calls use the cognition processor's admin credentials:
- `X-API-Key` header with configured API key
- `Authorization: Bearer` header
- `X-Client-ID` header with client ID

The processor acts as an admin to retrieve entries on behalf of users requesting justification.

#### Entry Content Parsing

The service handles two different entry content formats:

**USER entries** (simple format):
```json
{
  "role": "USER",
  "text": "I'd like to visit a country near the arctic circle"
}
```
→ Text extracted directly from `text` field

**AI entries** (complex format with events):
```json
{
  "role": "AI",
  "events": [
    {
      "eventType": "ContentFetched",
      "content": []
    },
    {
      "eventType": "PartialResponse",
      "chunk": "Great! Where..."
    },
    {
      "eventType": "Completed",
      "aiMessage": {
        "text": "Great! Where do you want your next travel destination to be?"
      }
    }
  ]
}
```
→ Text extracted from `events` array → `Completed` event → `aiMessage.text`

The `extractAiMessageText()` method iterates through the events array to find the `Completed` event and extract the final AI response text.

#### Error Handling

**Missing Entries**: If an entry is not found (archived/deleted), the service:
- Logs a warning
- Includes a placeholder entry with message: `[Entry not available - may be archived or deleted]`
- Continues processing remaining entries
- Does not fail the entire request

**Memory Not Found**: Returns HTTP 404 with error message

**Other Failures**: Returns HTTP 500 with error details

## Benefits

1. **Transparency** - Users can see exactly what conversation led to memory creation
2. **Debugging** - Developers can verify extraction quality and citation accuracy
3. **Trust** - Users can validate that memories accurately represent conversations
4. **Simplicity** - Clean, user-friendly response without technical clutter
5. **User Experience** - Human-readable justification instead of opaque UUIDs and metadata

## Usage Example

```bash
# Get justification for a specific memory
curl http://localhost:8090/api/memories/550e8400-e29b-41d4-a716-446655440000/justify

# Response shows:
# - Memory content, confidence, and citations
# - Conversation ID
# - Clean source entries (role, text, timestamp only)
```

## Testing

### Compilation
- ✅ Successful compilation with `./mvnw compile`
- ✅ All 301 source files compiled without errors
- ✅ Protobuf code generation successful

### Manual Testing Checklist

- [x] Start cognition processor with memory-service running
- [x] Create memories by having conversations
- [x] Call justify API with a valid memory ID
- [x] Verify response includes expanded entry content
- [x] Verify AI entries show correct text (not empty)
- [x] Test with non-existent memory ID (returns 404)
- [ ] Test with archived entries (should show placeholder)

## Files Created

- `src/main/java/io/github/rigazilla/memory/cognition/justify/MemoryJustifyResponse.java`
- `src/main/java/io/github/rigazilla/memory/cognition/justify/MemoryJustifyService.java`
- `src/main/java/io/github/rigazilla/memory/cognition/justify/MemoryJustifyResource.java`
- `DONE/018-memory-justify-api.md`

## Design Decisions

### Why Simplify the Response?

The initial implementation included all technical metadata (namespace, key, provenance details, entry metadata). This was overwhelming for users who just wanted to see "why was this memory created?"

**Simplified approach:**
- Removed: namespace, key, event_cursors, batch_trigger, source_hash, runtime_id, etc.
- Kept: Only user-relevant fields (content, confidence, citations, conversation_id, source entries)
- Result: Clean, focused API that answers "what conversation led to this memory?"

**Technical metadata is NOT lost** - it's still stored in memory-service and available through other admin APIs. We just filter it from this user-facing endpoint.

### Why Handle AI Entries Differently?

AI entries in memory-service use the `history/lc4j` content type with a complex events structure:
- Events track the LLM streaming process (ContentFetched, PartialResponse, Completed)
- The final AI message text is in `events[].Completed.aiMessage.text`
- Not in a simple `text` field like USER entries

Without special handling, AI entries would show empty text. The `extractAiMessageText()` method finds the `Completed` event and extracts the final response.

### Why Admin API?

The justify endpoint uses admin gRPC APIs because:
- Cognition processor already has admin role
- Needs to access entries across all users
- Can retrieve archived entries
- Simplifies authorization (no per-user token management)

### Why Expand Entries?

Rather than returning just entry IDs, we expand them because:
- Users need human-readable context
- Avoids requiring clients to make multiple API calls
- Provides complete justification in single response
- Better user experience

### Why Placeholders for Missing Entries?

When entries are missing/archived:
- Don't fail the entire request (partial data is better than none)
- Show placeholder to indicate data unavailability
- Log warning for debugging
- Allow users to still see available context

## Future Enhancements

### Phase 2 (Future)
- Add caching for frequently accessed entries
- Support batch justification requests (multiple memory IDs)
- Add filtering options (e.g., only show USER messages)
- Include verification reasoning if available

### Phase 3 (Future)
- Add LLM-generated natural language explanation
- Summarize long conversations
- Highlight relevant portions of entries
- Show confidence breakdown by citation

## Conclusion

The memory justify API provides transparency into the cognition processor's decision-making by expanding provenance entry IDs into simplified, human-readable conversation content. The response is intentionally minimal - showing only what users need to understand "why was this memory created?" while preserving all technical metadata in storage for debugging and auditing.

The implementation:
- ✅ Uses gRPC admin APIs for data access
- ✅ Handles both USER and AI entry formats correctly
- ✅ Provides clean, user-friendly REST API
- ✅ Filters technical metadata from response
- ✅ Handles errors gracefully
- ✅ Maintains security through admin authentication
