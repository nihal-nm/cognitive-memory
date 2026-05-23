# TODO: LLM Empty Response Handling

**Priority**: MEDIUM  
**Status**: Observed in production

## Problem

The LLM (Ollama llama3.2) sometimes returns empty memory candidates with blank content:

```json
{
  "facts": [
    {
      "type": "fact",
      "content": "",
      "confidence": 0,
      "citations": []
    }
  ],
  "preferences": [...],
  "procedures": [...],
  "problemSolutions": [...],
  "decisions": [...]
}
```

This causes `MemoryCandidate` constructor validation to fail:
```
IllegalArgumentException: Memory content cannot be null or blank
```

## Root Causes

### 1. Empty or Trivial Conversation Content
The transcript may contain no meaningful extractable information:
- System messages only
- Empty user messages
- Greetings without substance ("Hi", "Hello")

### 2. LLM Prompt Not Followed
The system prompt may not be clear enough, or the model may not understand the JSON schema requirements.

### 3. Model Limitations
llama3.2 may struggle with:
- Structured JSON output
- Following complex instructions
- Understanding the memory extraction task

## Current Behavior

**Pipeline fails** with `OutputParsingException` → `ValueInstantiationException` → `IllegalArgumentException`

**Impact**:
- Job marked as failed
- No memories extracted (even if some categories had valid content)
- Conversation never reprocessed (no retry logic)

## Solutions

### Option 1: Filter Empty Candidates (Recommended)

Add post-processing to filter out empty candidates before validation:

```java
// In DurableExtractionResponse or JobProcessor
public List<MemoryCandidate> getAllCandidates() {
    return List.of(facts, preferences, procedures, problemSolutions, decisions)
        .stream()
        .flatMap(List::stream)
        .filter(candidate -> candidate.content() != null && !candidate.content().isBlank())
        .filter(candidate -> candidate.confidence() > 0.0)
        .filter(candidate -> !candidate.citations().isEmpty())
        .toList();
}
```

**Pros**: Simple, handles LLM quirks gracefully  
**Cons**: Silently discards potentially useful data

### Option 2: Relax MemoryCandidate Validation

Allow empty content but filter later:

```java
public record MemoryCandidate(
    String type,
    String content,  // Allow empty
    double confidence,
    List<String> citations
) {
    public MemoryCandidate {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Memory type cannot be null or blank");
        }
        // Remove content validation
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
        }
        citations = citations != null ? List.copyOf(citations) : List.of();
    }
    
    public boolean isValid() {
        return content != null && !content.isBlank() 
            && confidence > 0.0 
            && !citations.isEmpty();
    }
}
```

**Pros**: More flexible, allows partial results  
**Cons**: Validation logic scattered across codebase

### Option 3: Improve LLM Prompt

Enhance system prompt to be more explicit:

```markdown
CRITICAL RULES:
1. If you find NO memories of a specific type, return an EMPTY ARRAY for that type
2. NEVER return objects with empty content fields
3. NEVER return objects with confidence 0
4. NEVER return objects with empty citations arrays

CORRECT (no facts found):
{
  "facts": [],
  "preferences": [...]
}

INCORRECT (empty object):
{
  "facts": [
    {
      "type": "fact",
      "content": "",
      "confidence": 0,
      "citations": []
    }
  ]
}
```

**Pros**: Fixes root cause  
**Cons**: May not work with all models, requires testing

### Option 4: Add Validation in Extractor

Validate LLM response before returning:

```java
@RegisterAiService
public interface DurableMemoryExtractor {
    
    @SystemMessage(fromResource = "prompts/durable-extractor-system.md")
    DurableExtractionResponse extract(String evidence);
    
    default DurableExtractionResponse extractAndValidate(String evidence) {
        DurableExtractionResponse response = extract(evidence);
        
        // Filter out invalid candidates
        return new DurableExtractionResponse(
            filterValid(response.facts()),
            filterValid(response.preferences()),
            filterValid(response.procedures()),
            filterValid(response.problemSolutions()),
            filterValid(response.decisions())
        );
    }
    
    private List<MemoryCandidate> filterValid(List<MemoryCandidate> candidates) {
        return candidates.stream()
            .filter(c -> c.content() != null && !c.content().isBlank())
            .filter(c -> c.confidence() > 0.0)
            .filter(c -> !c.citations().isEmpty())
            .toList();
    }
}
```

**Pros**: Centralized validation, clean separation  
**Cons**: Requires interface changes

## Recommended Approach

**Phase 1** (immediate): Implement Option 1 (filter empty candidates)
- Add filtering in `DurableExtractionResponse.getAllCandidates()`
- Log warning when empty candidates are filtered
- Continue processing with valid candidates only

**Phase 2** (short-term): Implement Option 3 (improve prompt)
- Update system prompt with explicit rules
- Test with various conversation types
- Monitor for improvement

**Phase 3** (long-term): Consider Option 2 or 4
- If filtering is insufficient, relax validation
- Add comprehensive validation layer

## Testing

```bash
# Test with empty conversation
curl -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
  -H "X-API-Key: admin-api-key-1" \
  -H "Authorization: Bearer admin-api-key-1" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "HISTORY",
    "content": [{"role": "user", "text": "Hi"}]
  }'

# Wait for processing, check logs
tail -f /tmp/quarkus-startup-final.log | grep "Empty candidates filtered"

# Test with meaningful conversation
curl -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
  -H "X-API-Key: admin-api-key-1" \
  -H "Authorization: Bearer admin-api-key-1" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "HISTORY",
    "content": [{"role": "user", "text": "I work at Acme Corp as a senior engineer"}]
  }'

# Verify memories extracted
```

## Metrics

Track empty response rate:
```java
Counter emptyResponseCounter = registry.counter("cognition.extraction.empty_responses");
Counter validCandidatesCounter = registry.counter("cognition.extraction.valid_candidates");
Counter filteredCandidatesCounter = registry.counter("cognition.extraction.filtered_candidates");
```

## Related Issues

- LLM prompt engineering (see `TODO/configuration-improvements.md`)
- Retry logic (see `TODO/retry-logic.md`)
- Model selection and configuration

## References

- LangChain4j output parsing: https://docs.langchain4j.dev/tutorials/output-parsing
- Ollama model capabilities: https://ollama.ai/library/llama3.2
