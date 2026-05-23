# Session Notes: 2026-05-22 & 2026-05-23

## Issues Resolved (2026-05-22)

### 1. Request Context Not Active (FIXED)
**Problem**: `ContextNotActiveException` when calling `@RegisterAiService` beans from virtual threads.

**Solution**: Manually activate/terminate request context in `JobProcessor.processJob()`:
```java
ManagedContext requestContext = Arc.container().requestContext();
if (!requestContext.isActive()) {
    requestContext.activate();
}
try {
    processJobInternal(job, startTime);
} finally {
    if (requestContext.isActive()) {
        requestContext.terminate();
    }
}
```

**Files Modified**: `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`

### 2. Ollama Timeout Too Short (FIXED)
**Problem**: 10-second default timeout causing `NoStackTraceTimeoutException` during LLM calls.

**Solution**: Comprehensive timeout configuration in `application.properties`:
- Named model timeout: `quarkus.langchain4j.memory.chat-model.timeout=120s`
- Ollama client: `quarkus.langchain4j.ollama.timeout=120s`
- REST client specific: `quarkus.rest-client."dev.langchain4j.model.ollama.OllamaClient".read-timeout=120000`
- Global REST client: `quarkus.rest-client.read-timeout=120000`
- Vert.x HTTP client: `quarkus.vertx.http-client-options.idle-timeout=120`

**Files Modified**: `src/main/resources/application.properties`

## Issues Identified (NOT FIXED - Left as TODO)

### 3. Memory Write Authorization (TODO)
**Problem**: `PERMISSION_DENIED: access denied` when writing memories via `MemoriesService.PutMemory`.

**Root Cause**: Admin credentials don't grant permission to write to user memory namespaces.

**Partial Implementation**: Added `RequestActor` with `on_behalf_of_user_id` to `MemoryWriter.java` but NOT compiled or tested.

**Status**: Code changes made but user requested to leave as TODO for proper testing.

**Next Steps**:
1. Compile and test RequestActor integration
2. Verify memory writes succeed with on-behalf-of authorization
3. Load real user ID from conversation metadata (currently using "user-placeholder")

**Files Modified**: 
- `src/main/java/io/github/rigazilla/memory/cognition/writer/MemoryWriter.java` (untested)
- `TODO/authentication-authorization.md` (updated with status)

## Observations (2026-05-23)

### 4. Event Stream Invalidation (DOCUMENTED)
**Observation**: After restarting memory-service and cognition processor, receiving "invalidate" events:
```
Event Type:  invalidate
Kind:        stream
Reason:      cursor beyond retention window
```

**Meaning**: The checkpoint cursor is older than the event stream's retention window. Events between the old checkpoint and retention boundary are lost.

**Current Behavior**: 
- Events are logged but no special handling
- Processor continues with newer events
- Potential data loss for missed events

**Impact**: 
- Events that occurred while processor was offline (beyond retention window) are permanently lost
- No automatic recovery or backfill mechanism

**Documentation**: Created `TODO/event-stream-invalidation.md` with:
- Problem description
- Three solution options (reset, backfill, accept loss)
- Recommended approach (reset and start fresh)
- Testing scenarios
- Configuration suggestions

**Workaround**: Delete checkpoint file before restart:
```bash
rm /tmp/cognition-checkpoints/worker-1.json
```

**Status**: Documented for future implementation. Not critical for development/testing phase.

### 5. LLM Empty Response Handling (DOCUMENTED)
**Observation**: LLM (Ollama llama3.2) sometimes returns empty memory candidates:
```json
{
  "facts": [{"type": "fact", "content": "", "confidence": 0, "citations": []}],
  "preferences": [...],
  ...
}
```

**Error**: `OutputParsingException` → `ValueInstantiationException` → `IllegalArgumentException: Memory content cannot be null or blank`

**Root Causes**:
1. Empty or trivial conversation content (greetings, system messages)
2. LLM not following JSON schema requirements
3. Model limitations with structured output

**Impact**:
- Job fails completely (no partial results)
- No memories extracted even if some categories had valid content
- Conversation never reprocessed (no retry logic)

**Documentation**: Created `TODO/llm-empty-responses.md` with:
- Problem analysis
- Four solution options:
  1. Filter empty candidates (recommended)
  2. Relax validation
  3. Improve LLM prompt
  4. Add validation layer
- Recommended approach (filter + improve prompt)
- Testing scenarios
- Metrics suggestions

**Status**: Documented for future implementation. Affects production reliability.

## Current Pipeline Status

✅ **Working**:
- Event stream subscription (admin scope)
- Event stream invalidation detection (logged)
- Debounce window creation and promotion
- Job queue and processing
- Transcript loading (with auth workaround)
- LLM extraction (with 120s timeout, but may return empty)
- LLM verification (with 120s timeout)

❌ **Blocked**:
- Memory writing (PERMISSION_DENIED)
- End-to-end testing
- Empty LLM responses cause job failures

⚠️ **Known Issues**:
- User ID placeholder: All operations use "user-placeholder" instead of real user ID
- No conversation metadata loading
- RequestActor code added but not tested
- Event stream invalidation not handled (data loss possible)
- LLM empty responses not filtered (job failures)

## Recommendations for Next Session

1. **HIGH PRIORITY**: Fix LLM empty response handling
   - Implement filtering in `DurableExtractionResponse.getAllCandidates()`
   - Update system prompt with explicit rules
   - Test with empty and meaningful conversations

2. **HIGH PRIORITY**: Test RequestActor integration in MemoryWriter
   - Compile the code
   - Restart application
   - Verify memory writes succeed
   - Check memories are written to correct namespace

3. **HIGH PRIORITY**: Implement conversation metadata loading
   - Add ConversationsService client to JobProcessor
   - Load conversation owner user ID
   - Replace "user-placeholder" with real user ID
   - Update both TranscriptLoader and MemoryWriter

4. **MEDIUM PRIORITY**: Implement event stream invalidation handling
   - Add special case for "invalidate" events
   - Clear dirty windows and checkpoint
   - Reconnect from beginning
   - Log warning about data loss

5. **MEDIUM PRIORITY**: Verify named model configuration
   - Check if LangChain4j warnings are resolved
   - Verify temperature and token limits are applied

6. **LOW PRIORITY**: Add basic tests
   - Unit tests for core components
   - Integration test for end-to-end pipeline

## Files Modified This Session

### 2026-05-22
1. `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`
   - Added manual request context activation
   - Split into `processJob()` and `processJobInternal()`

2. `src/main/resources/application.properties`
   - Increased all timeout configurations to 120s
   - Added Vert.x HTTP client timeout settings

3. `src/main/java/io/github/rigazilla/memory/cognition/writer/MemoryWriter.java`
   - Added RequestActor import
   - Added `.setActor()` to PutMemoryRequest
   - **NOT COMPILED OR TESTED**

4. `TODO/authentication-authorization.md`
   - Updated with partial implementation status

5. `TODO/session-notes.md`
   - Created this file

### 2026-05-23
6. `TODO/event-stream-invalidation.md`
   - Documented invalidation event handling
   - Three solution options with recommendations
   - Testing and configuration guidance

7. `TODO/llm-empty-responses.md`
   - Documented LLM empty response problem
   - Four solution options with recommendations
   - Testing scenarios and metrics

8. `TODO/session-notes.md`
   - Updated with 2026-05-23 observations
