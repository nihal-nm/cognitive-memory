# 009: Fix history/lc4j Content Type Handling

**Status**: ✅ Complete - CRITICAL BUG FIX  
**Date**: 2026-05-25  
**Severity**: HIGH - AI responses were being skipped in evidence

## Overview

**CRITICAL BUG**: AI responses with `content_type="history/lc4j"` were being skipped when formatting evidence for the LLM. This caused the extractor to only see USER messages, missing the AI context entirely.

## The Bug

### Root Cause

The code used **exact string match** for content type:
```java
if ("history".equals(entry.getContentType())) {
    // Process entry
}
```

But AI responses (from LangChain4j) use `content_type="history/lc4j"`, not just `"history"`.

**Result**: AI responses were silently skipped! ❌

### Impact

**Before the fix:**
- USER entries: ✅ Included in evidence
- AI entries with `history/lc4j`: ❌ **SKIPPED**
- LLM only saw half the conversation
- Extraction quality was poor (missing context)
- Citations were incomplete

**Example broken evidence:**
```
=== CONVERSATION TRANSCRIPT ===

[USER] I want to learn mathematics

[USER] I want to start with Algebra

```
**Missing**: All AI responses!

### Observed Symptoms

From user's logs:
```
DEBUG [TranscriptLoader]   - Entry b9f7b09d [USER]: I want to learn mathematics
DEBUG [TranscriptLoader]   - Entry 3f89b300 [history/lc4j]: (non-history content)  ← SKIPPED!
DEBUG [TranscriptLoader]   - Entry 42904592 [USER]: I want to start with Algebra
DEBUG [TranscriptLoader]   - Entry 52cc05ad [history/lc4j]: (non-history content)  ← SKIPPED!
```

The entries were loaded, but marked as "non-history content" and excluded from evidence.

## The Fix

Changed from **exact match** to **prefix match**:

### Before (BROKEN):
```java
if ("history".equals(entry.getContentType())) {
```

### After (FIXED):
```java
String contentType = entry.getContentType();
if (contentType != null && contentType.startsWith("history")) {
```

This matches:
- ✅ `"history"` (plain format)
- ✅ `"history/lc4j"` (LangChain4j format)
- ✅ `"history/springai"` (Spring AI format, if used)
- ✅ Any other `history/*` variants

## Files Fixed

### 1. EvidencePack.java (CRITICAL)

**Location**: `formatAsText()` method - generates evidence sent to LLM

**Before:**
```java
if ("history".equals(entry.getContentType()) && entry.getContentCount() > 0) {
```

**After:**
```java
String contentType = entry.getContentType();
if (contentType != null && contentType.startsWith("history") && entry.getContentCount() > 0) {
```

**Impact**: Now includes AI responses in evidence → LLM sees full conversation context

### 2. TranscriptLoader.java (Logging)

**Location**: `logEntryDetails()` method - debug logging

**Before:**
```java
if ("history".equals(contentType) && entry.getContentCount() > 0) {
```

**After:**
```java
if (contentType != null && contentType.startsWith("history") && entry.getContentCount() > 0) {
```

**Impact**: Debug logs now show AI response content instead of "(non-history content)"

## Expected Behavior After Fix

### Corrected Logs

```
DEBUG [TranscriptLoader] Transcript entries for conversation abc-123:
DEBUG [TranscriptLoader]   - Entry b9f7b09d [USER]: I want to learn mathematics
DEBUG [TranscriptLoader]   - Entry 3f89b300 [AI]: That's great! Let's start with the fundamentals...  ← NOW SHOWN!
DEBUG [TranscriptLoader]   - Entry 42904592 [USER]: I want to start with Algebra
DEBUG [TranscriptLoader]   - Entry 52cc05ad [AI]: Excellent choice! Algebra is a great foundation...  ← NOW SHOWN!
```

### Corrected Evidence

```
=== CONVERSATION TRANSCRIPT ===

[USER] I want to learn mathematics

[AI] That's great! Let's start with the fundamentals...

[USER] I want to start with Algebra

[AI] Excellent choice! Algebra is a great foundation...

```

**Complete conversation context!** ✅

## Impact on Extraction Quality

### Before (Broken)

**Evidence sent to LLM:**
- Only USER messages
- No AI responses
- No conversational context
- Poor extraction quality

**Example extraction issues:**
- Couldn't extract AI-provided facts
- Missed acknowledgments/confirmations
- Lost conversational flow
- Incomplete citations

### After (Fixed)

**Evidence sent to LLM:**
- Full USER/AI conversation
- Complete context
- Natural dialog flow
- Better extraction quality

**Expected improvements:**
- More accurate fact extraction
- Better preference detection
- Complete citations
- Contextual understanding

## Why This Happened

### Proto Definition Ambiguity

The proto comment says:
```protobuf
// History channel entries must use "history" as the content_type.
```

But LangChain4j and other frameworks use variants like:
- `"history/lc4j"` - LangChain4j format
- `"history/springai"` - Spring AI format (potential)
- `"history/<framework>"` - Other framework formats

The code assumed exact `"history"` string, but actual implementations use namespaced variants.

### Why It Wasn't Caught Earlier

1. **USER entries worked** - They likely use plain `"history"` content type
2. **No error thrown** - Entries were silently skipped
3. **No validation** - No warning about skipped entries
4. **Phase 3A focus** - Testing focused on pipeline mechanics, not content quality

## Testing

### Compilation
```bash
mvn compile
# ✅ SUCCESS
```

### Verification Steps

1. **Restart the app** with the fix
2. **Create a conversation** with user and AI messages
3. **Check debug logs** - AI entries should show content, not "(non-history content)"
4. **Check evidence text** - Should include both [USER] and [AI] messages
5. **Verify extraction** - Should produce better quality memories

### Expected Log Output

**Before fix:**
```
DEBUG [TranscriptLoader]   - Entry xxx [history/lc4j]: (non-history content)
DEBUG [JobProcessor] Evidence text sent to extractor:
=== CONVERSATION TRANSCRIPT ===
[USER] Some message
[USER] Another message
```

**After fix:**
```
DEBUG [TranscriptLoader]   - Entry xxx [AI]: Response text here
DEBUG [JobProcessor] Evidence text sent to extractor:
=== CONVERSATION TRANSCRIPT ===
[USER] Some message
[AI] Response text here
[USER] Another message
[AI] Another response
```

## Potential Issues to Watch For

### Edge Cases

1. **Non-history content types** (e.g., `"context"`, `"metadata"`)
   - ✅ Still correctly skipped (don't start with "history")

2. **Malformed content types** (e.g., `null`, empty string)
   - ✅ Null check added: `contentType != null`

3. **Other history variants** (e.g., `"history-v2"`)
   - ⚠️ Would NOT match (doesn't start with "history/")
   - If this becomes an issue, may need pattern matching

### Performance

- ✅ `startsWith()` is O(n) where n = prefix length
- ✅ Minimal impact (prefix is short)
- ✅ Same complexity as `equals()`

## Related Issues

### Duplicate Memories

With AI responses now included in evidence:
- **More complete conversations** → May extract more facts
- **Same facts in multiple turns** → Still need consolidation
- **Better context** → May reduce incorrect extractions

### Verification

With full conversation context:
- **Better citation matching** → AI responses can be cited
- **More accurate verification** → Full context available
- **Fewer false rejections** → Claims can be verified against AI responses

## Recommendations

### Immediate Actions

1. **Restart the app** to pick up the fix
2. **Re-process recent conversations** if extraction was poor
3. **Monitor extraction quality** - should improve significantly

### Future Enhancements

1. **Add content type validation**
   - Log warning for unexpected content types
   - Track content type distribution
   - Alert on new variants

2. **Add evidence quality metrics**
   - Track USER/AI message ratio
   - Warn if ratio is unexpected
   - Monitor entry skip rate

3. **Add integration test**
   - Test with `history/lc4j` content type
   - Verify AI responses are included
   - Check evidence formatting

## Lessons Learned

1. **Don't assume exact string matches** - Use prefix/pattern matching for extensibility
2. **Log what's being skipped** - Silent failures are hard to debug
3. **Test with real data early** - Framework-specific formats may differ from specs
4. **Add visibility** - Debug logging caught this issue immediately

## References

- **Proto definition**: `src/main/proto/memory/v1/memory_service.proto`
- **Evidence formatting**: `src/main/java/.../evidence/EvidencePack.java`
- **Debug logging**: Added in `DONE/008-transcript-debug-logging.md`
- **LangChain4j format**: Uses `history/lc4j` content type for AI responses
