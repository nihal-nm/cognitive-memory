# TODO: Fix Prompt Example Contamination

**Status**: 🔴 Open  
**Priority**: Medium  
**Discovered**: 2026-05-25  
**Affected Component**: Extraction prompts

## Problem

The LLM (llama3.2:1.5b) is copying example facts from the system prompt instead of extracting from actual conversation evidence.

### Observed Symptoms

```
Evidence sent to extractor:
  [USER] I want to write a romance
  [AI] Sure, I can help you write that! ...
  [USER] I want to write a contemporary romance
  [AI] Great choice! What's the setting...

Extracted (WRONG):
  - [fact] User works at Acme Corp - confidence: 0.95, citations: 0
  - [preference] User prefers dark mode - confidence: 0.50, citations: 0
```

These facts are **not** in the conversation - they are **examples from the prompt**!

### Root Cause

The extraction system prompt (`src/main/resources/prompts/durable-extractor-system.md`) contains example facts:

```markdown
### Facts
- Examples: "User works at Acme Corp", "User's database is PostgreSQL 15"

### Preferences  
- Examples: "User prefers dark mode", "User likes concise explanations"

## Example Output Structure
{
  "facts": [
    {
      "content": "User works at Acme Corp as a senior engineer",
      "confidence": 0.95,
      "citations": ["I work at Acme Corp", "I'm a senior engineer here"]
    }
  ]
}
```

**Small models (1.5B-3B params) copy these examples literally** when they can't find clear facts in the evidence.

### Why It Happens

1. **Model size** - llama3.2:1.5b is small, struggles to distinguish "example" from "instruction"
2. **Prominent examples** - The examples use realistic values that look like actual facts
3. **Template filling** - Model sees JSON template and fills it with available examples
4. **Weak evidence** - When conversation lacks extractable facts, model falls back to examples

### Why It Gets Filtered

The pipeline correctly catches these:
```
reason: no citations, confidence: 0.95, citations: 0
```

The LLM copies content and confidence but **cannot generate citations** because the facts aren't in the evidence. The extraction filter removes them. ✅

**Impact**: Pipeline is working correctly by filtering these out, but wasting LLM tokens/time on invalid extractions.

## Solution Options

### Option 1: Remove Specific Examples (Recommended for Small Models)

**Change:**
```markdown
### Facts
- Examples: "User works at [COMPANY]", "User's database is [DATABASE] [VERSION]"

### Preferences
- Examples: "User prefers [OPTION] over [ALTERNATIVE]", "User likes [STYLE] explanations"
```

Or remove examples entirely:
```markdown
### Facts
Objective, verifiable information about the user, their environment, or their work.
Extract employment details, technical stack, personal information.
Only extract what is explicitly stated in the conversation transcript.
```

**Pros:**
- Eliminates contamination source
- Still shows format/structure
- Works with small models

**Cons:**
- Less concrete guidance
- May reduce extraction quality for edge cases

### Option 2: Add Explicit Warning

**Add to prompt:**
```markdown
⚠️ CRITICAL INSTRUCTION:
The examples above are ONLY for illustration of the format.
DO NOT extract these example facts unless they actually appear in the conversation.
ONLY extract information that is explicitly present in the evidence below.
If no facts are found in the evidence, return empty arrays.
```

**Pros:**
- Keeps concrete examples
- May work for larger models

**Cons:**
- Small models often ignore warnings
- Adds prompt length

### Option 3: Use Obviously Fake Examples

**Change:**
```markdown
### Facts
- Examples: "[EXAMPLE: User works at COMPANY_NAME]", "[EXAMPLE: User's database is DATABASE_TYPE VERSION]"

## Example Output Structure
{
  "facts": [
    {
      "content": "[EXAMPLE FACT: Replace with actual extracted fact]",
      "confidence": 0.95,
      "citations": ["[Quote from evidence]"]
    }
  ]
}
```

**Pros:**
- Clearly marked as examples
- Shows structure without realistic values

**Cons:**
- Less natural for model to follow
- May confuse format parsing

### Option 4: Use Larger Model

**Change model:**
```properties
# From:
quarkus.langchain4j.memory.chat-model.model-id=llama3.2

# To:
quarkus.langchain4j.memory.chat-model.model-id=llama3.2:7b
# or
quarkus.langchain4j.memory.chat-model.model-id=llama3.2:14b
```

**Pros:**
- Better instruction following
- Less likely to copy examples
- Better overall extraction quality

**Cons:**
- Slower inference
- More memory/compute
- May still have some contamination

### Option 5: Separate Instruction and Example Phases

**Restructure prompt:**
```markdown
## Instructions
[All instructions here]

## Evidence to Analyze
{{evidence}}

## Output Format
Return JSON with these fields:
- type, content, confidence, citations

DO NOT use placeholder values. Extract only from the evidence above.
```

Remove example JSON entirely, or put it in a separate section clearly marked as "Reference Format (not to be extracted)".

**Pros:**
- Clear separation of concerns
- Less contamination risk

**Cons:**
- Requires prompt restructuring
- May reduce format compliance

## Recommended Approach

**Phase 1: Quick Fix (Now)**
- Update `durable-extractor-system.md` to use generic placeholders
- Change "User works at Acme Corp" → "User works at [COMPANY]"
- Change "User prefers dark mode" → "User prefers [OPTION]"

**Phase 2: Model Upgrade (When Ready)**
- Test with llama3.2:7b or larger
- Compare extraction quality and speed
- May allow keeping concrete examples

**Phase 3: Prompt Refinement (Long Term)**
- A/B test different prompt structures
- Track contamination rate metrics
- Iterate based on real-world performance

## Verification

After implementing fix, verify:

1. **No contamination** - Extracted facts should NOT match prompt examples
2. **Empty arrays OK** - If evidence has no facts, arrays should be empty
3. **Citation rate** - Most extractions should have citations
4. **Quality maintained** - Real facts still extracted correctly

### Test Cases

Create test conversations:
- **No extractable facts** - Should return empty arrays (not prompt examples)
- **Clear facts** - Should extract with high confidence and citations
- **Edge cases** - Implied facts, partial information

### Metrics to Track

```bash
# Count "Acme Corp" contamination
grep "Acme Corp" logs/quarkus.log | wc -l

# Should be 0 after fix (unless actually in conversations)

# Count "dark mode" contamination  
grep "dark mode" logs/quarkus.log | wc -l

# Should be 0 after fix (unless actually in conversations)

# Count filtered candidates with no citations
grep "no citations" logs/quarkus.log | wc -l

# Should decrease significantly after fix
```

## Related Issues

- **Small model limitations** - Consider model upgrade (TODO/model-evaluation.md if exists)
- **Prompt engineering** - General prompt quality improvements
- **Verification prompt** - Check if verifier has same issue (TODO: check `durable-verifier-system.md`)

## Impact

**Current State:**
- ⚠️ LLM wastes tokens extracting invalid facts
- ⚠️ Filtering catches them, but inefficient
- ✅ No bad data reaches storage (filtering works)
- ⚠️ User sees warning logs for phantom facts

**After Fix:**
- ✅ Cleaner extraction output
- ✅ Fewer filtered candidates
- ✅ Less log noise
- ✅ Faster processing (fewer invalid candidates to filter/verify)

## Implementation

### Files to Modify

1. `src/main/resources/prompts/durable-extractor-system.md`
   - Lines 15, 22, 29, 36, 43 - Memory type examples
   - Lines 66-88 - JSON example output structure

2. `src/main/resources/prompts/durable-verifier-system.md` (TODO: check)
   - May have similar contamination risk

### Estimated Effort

- **Option 1** (Generic placeholders): 15 minutes
- **Option 2** (Add warning): 5 minutes
- **Option 3** (Fake examples): 30 minutes
- **Option 4** (Model upgrade): 1 hour (includes testing)
- **Option 5** (Restructure): 2 hours

## Testing Plan

1. **Update prompt** with chosen fix
2. **Restart app** to load new prompt
3. **Create test conversation** with no clear facts (e.g., "I want to write a song")
4. **Check extraction output** - Should have empty arrays or only song-related facts
5. **Check logs** - No "Acme Corp" or "dark mode" unless actually discussed
6. **Verify real extractions** - Create conversation with actual facts, confirm still extracted

## References

- **Discovered in**: Session analyzing invalid candidate logging
- **Prompt file**: `src/main/resources/prompts/durable-extractor-system.md`
- **Related**: Small model behavior, prompt engineering best practices
- **LangChain4j docs**: Prompt template guidelines
