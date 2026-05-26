# 007: Enhanced Invalid Candidate Logging

**Status**: ✅ Complete  
**Date**: 2026-05-25

## Overview

Added detailed DEBUG-level logging for invalid memory candidates that are filtered out during extraction and verification phases. This provides visibility into what's being rejected and why, ensuring nothing is silently lost.

**Log Level**: DEBUG - Enable with `quarkus.log.category."io.github.rigazilla.memory".level=DEBUG` to see invalid candidate details.

## Problem Solved

**Before:** 
- Generic warning: "Filtered N invalid candidates"
- No visibility into WHAT was filtered
- No clarity on WHY candidates were rejected
- Difficult to debug LLM extraction issues

**After:**
- Detailed log entry for each invalid candidate
- Shows type, content preview, reason, confidence, and citation count
- Same detail for verification rejections
- Easy to audit and debug extraction quality

## Implementation

### 1. Enhanced DurableExtractionResponse

Added methods to expose invalid candidates:

```java
/**
 * Get all invalid candidates that were filtered out.
 */
public List<MemoryCandidate> getInvalidCandidates() {
    return List.of(facts, preferences, procedures, problemSolutions, decisions)
        .stream()
        .flatMap(List::stream)
        .filter(candidate -> !isValidCandidate(candidate))
        .toList();
}

/**
 * Get reason why a candidate is invalid.
 */
public String getInvalidReason(MemoryCandidate candidate) {
    if (candidate.content() == null || candidate.content().isBlank()) {
        return "empty or blank content";
    }
    if (candidate.confidence() <= 0.0) {
        return String.format("zero/negative confidence (%.2f)", candidate.confidence());
    }
    if (candidate.citations() == null || candidate.citations().isEmpty()) {
        return "no citations";
    }
    return "unknown";
}
```

### 2. Enhanced JobProcessor Logging

#### Extraction Phase (Stage 2)

```java
if (filteredCount > 0) {
    LOG.debugf("  ⚠ Filtered %d invalid candidates:", filteredCount);
    for (MemoryCandidate invalid : invalidCandidates) {
        String reason = extraction.getInvalidReason(invalid);
        String preview = invalid.content() != null && invalid.content().length() > 50
            ? invalid.content().substring(0, 47) + "..."
            : (invalid.content() != null ? invalid.content() : "(null)");
        LOG.debugf("    - [%s] %s - reason: %s, confidence: %.2f, citations: %d",
            invalid.type(),
            preview,
            reason,
            invalid.confidence(),
            invalid.citations() != null ? invalid.citations().size() : 0);
    }
}
```

#### Verification Phase (Stage 3)

```java
if (!verification.rejected().isEmpty()) {
    LOG.debugf("  ⚠ Rejected %d candidates during verification:", verification.rejected().size());
    for (var rejected : verification.rejected()) {
        String preview = rejected.candidate().content().length() > 50
            ? rejected.candidate().content().substring(0, 47) + "..."
            : rejected.candidate().content();
        LOG.debugf("    - [%s] %s - reason: %s, confidence: %.2f",
            rejected.candidate().type(),
            preview,
            rejected.reason(),
            rejected.candidate().confidence());
    }
}
```

## Example Log Output

**Note**: These logs appear only when DEBUG level is enabled for `io.github.rigazilla.memory`.

### Invalid Extraction Candidates

```
2026-05-25 17:30:15 INFO  [JobProcessor]   [2/5] Extracting memories from evidence
2026-05-25 17:30:16 DEBUG [JobProcessor]   ⚠ Filtered 3 invalid candidates:
2026-05-25 17:30:16 DEBUG [JobProcessor]     - [fact] (null) - reason: empty or blank content, confidence: 0.00, citations: 0
2026-05-25 17:30:16 DEBUG [JobProcessor]     - [preference] User likes pizza - reason: no citations, confidence: 0.85, citations: 0
2026-05-25 17:30:16 DEBUG [JobProcessor]     - [decision] Decided to use React - reason: zero/negative confidence (0.00), confidence: 0.00, citations: 2
2026-05-25 17:30:16 INFO  [JobProcessor]   ✓ Extracted 5 valid memory candidates (raw=8, filtered=3): facts=2, preferences=2, procedures=0, problemSolutions=1, decisions=0
```

### Rejected Verification Candidates

```
2026-05-25 17:30:17 INFO  [JobProcessor]   [3/5] Verifying memory candidates
2026-05-25 17:30:18 INFO  [JobProcessor]   ✓ Verification complete: verified=4, rejected=1
2026-05-25 17:30:18 DEBUG [JobProcessor]   ⚠ Rejected 1 candidates during verification:
2026-05-25 17:30:18 DEBUG [JobProcessor]     - [fact] User works at Acme Corp - reason: Citation "works at Acme" not found in evidence, confidence: 0.75
```

## Rejection Reasons

### Extraction Phase Filters

Invalid candidates are filtered for:

1. **Empty or blank content**
   - `content == null` or `content.isBlank()`
   - LLM returned empty memory

2. **Zero/negative confidence**
   - `confidence <= 0.0`
   - LLM didn't assign confidence or assigned invalid value

3. **No citations**
   - `citations == null` or `citations.isEmpty()`
   - Memory has no supporting evidence

### Verification Phase Rejections

Verifier rejects candidates for:

1. **Citation not found in evidence**
   - Citation text doesn't match evidence pack content
   - LLM hallucinated or paraphrased too much

2. **Unsupported by evidence**
   - Content contradicts or isn't supported by evidence
   - LLM made logical leap without basis

3. **Low verification confidence**
   - Verifier assigns low confidence to claim
   - Evidence is weak or ambiguous

## Benefits

### 1. Debugging LLM Extraction ✅
- See what the LLM is producing
- Identify patterns in invalid candidates
- Tune prompts to reduce invalid outputs

### 2. Quality Assurance ✅
- Verify nothing is silently lost
- Audit filtering decisions
- Ensure extraction quality

### 3. Prompt Engineering ✅
- If many "no citations" rejections → improve extraction prompt
- If many "empty content" → LLM isn't following format
- If many verification rejections → improve evidence formatting

### 4. Transparency ✅
- Clear visibility into pipeline filtering
- Audit trail for rejected memories
- Confidence in filtering logic

## Usage

### Enable DEBUG Logging

Ensure DEBUG level is enabled in `application.properties`:
```properties
quarkus.log.category."io.github.rigazilla.memory".level=DEBUG
```

Without DEBUG level, you'll only see the count summary in INFO logs (e.g., "filtered=3").

### Monitoring Invalid Candidates

```bash
# View recent invalid candidates
tail -100 logs/quarkus.log | grep "⚠ Filtered"

# Count rejection reasons
grep "reason:" logs/quarkus.log | \
  sed 's/.*reason: //' | \
  cut -d',' -f1 | \
  sort | uniq -c | sort -rn

# Find high-confidence rejections (potential issues)
grep "confidence: 0\.[89]" logs/quarkus.log | grep "⚠"
```

### Analyzing Patterns

```bash
# Most common rejection type
grep "\[fact\]\|\[preference\]\|\[procedure\]" logs/quarkus.log | \
  grep "⚠" | \
  sed 's/.*\[\([^]]*\)\].*/\1/' | \
  sort | uniq -c | sort -rn

# Empty content rejections
grep "empty or blank content" logs/quarkus.log | wc -l

# No citations rejections  
grep "no citations" logs/quarkus.log | wc -l

# Zero confidence rejections
grep "zero/negative confidence" logs/quarkus.log | wc -l
```

## Files Modified

- `src/main/java/io/github/rigazilla/memory/cognition/extraction/DurableExtractionResponse.java`
  - Added `getInvalidCandidates()` method
  - Added `getInvalidReason()` method

- `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`
  - Enhanced extraction logging with invalid candidate details
  - Enhanced verification logging with rejected candidate details

## Testing

### Compilation
```bash
mvn compile
# ✅ SUCCESS
```

### Expected Behavior

When processing a job:
1. **All invalid candidates from extraction are logged** with type, preview, reason, confidence, citations
2. **All rejected candidates from verification are logged** with type, preview, reason, confidence
3. **Warnings clearly indicate** filtering vs. verification rejection
4. **No silent failures** - everything is auditable

### What to Watch For

**Normal patterns:**
- Occasional "no citations" (LLM didn't cite)
- Rare "empty content" (LLM format error)
- Verification rejections on low-confidence extractions

**Warning signs:**
- Many "empty content" rejections → LLM prompt issue
- Many "no citations" → LLM not following citation instructions
- High-confidence rejections (>0.8) → Verifier too strict or evidence formatting issue

## Next Steps

### Optional Enhancements

1. **Aggregate metrics**
   - Track rejection rates over time
   - Alert on abnormal patterns
   - Dashboard for extraction quality

2. **Persist invalid candidates**
   - Store to database for analysis
   - Enable historical trending
   - Support prompt tuning

3. **Auto-retry with modified prompts**
   - If rejection rate > threshold, adjust prompts
   - Experiment with different extraction strategies
   - A/B test prompt variations

## References

- **Phase 3**: Job Processing Pipeline (`DONE/003-job-processing-pipeline.md`)
- **Known Limitation**: "No retry logic" - invalid candidates are logged but not retried
- **MemoryCandidate validation**: `MemoryCandidate.isValid()` method
