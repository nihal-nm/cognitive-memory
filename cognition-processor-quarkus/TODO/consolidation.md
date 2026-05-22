# TODO: Memory Consolidation

**Priority**: LOW  
**Status**: Deferred to future phase

## Problem

Duplicate memories will be stored if the same facts appear in multiple conversation batches.

## Current Behavior

- Each job processes independently
- No deduplication across batches
- Same fact extracted multiple times → multiple memory entries
- Example: "User works at Acme Corp" extracted in batch 1 and batch 2 → 2 separate memories

## Impact

- Memory storage bloat
- Redundant entries in search results
- Inconsistent confidence scores across duplicates
- Harder to maintain memory accuracy

## Design (Phase 3F - Skipped)

### Consolidation Strategy

1. **Before Writing**: Check for existing similar memories
2. **Similarity Detection**: Use semantic search or exact content matching
3. **Merge Logic**: 
   - Keep highest confidence version
   - Merge citations from all versions
   - Update revision number
4. **Conflict Resolution**: Handle contradictory facts

### Implementation Approach

```java
public class MemoryConsolidator {
    
    @Inject
    MemoriesServiceGrpc.MemoriesServiceBlockingStub memoriesStub;
    
    /**
     * Consolidate new candidates with existing memories.
     * Returns deduplicated list ready for writing.
     */
    public List<MemoryCandidate> consolidate(
            String userId, 
            List<MemoryCandidate> newCandidates) {
        
        List<MemoryCandidate> consolidated = new ArrayList<>();
        
        for (MemoryCandidate candidate : newCandidates) {
            // Search for similar existing memories
            List<MemoryItem> existing = searchSimilar(userId, candidate);
            
            if (existing.isEmpty()) {
                // New memory, add as-is
                consolidated.add(candidate);
            } else {
                // Merge with existing
                MemoryCandidate merged = merge(candidate, existing);
                if (merged != null) {
                    consolidated.add(merged);
                }
            }
        }
        
        return consolidated;
    }
    
    private List<MemoryItem> searchSimilar(String userId, MemoryCandidate candidate) {
        // Use SearchMemories with semantic search
        // Or exact content matching
        // Return memories with similarity > threshold
    }
    
    private MemoryCandidate merge(MemoryCandidate newCandidate, List<MemoryItem> existing) {
        // Merge logic:
        // - Keep highest confidence
        // - Combine citations
        // - Detect contradictions
        // - Return null if contradiction cannot be resolved
    }
}
```

### Revision-Aware Updates

Use Enhancement 101's conditional writes:

```java
// Update existing memory with merged content
UpdateMemoryRequest request = UpdateMemoryRequest.newBuilder()
    .addAllNamespace(namespace)
    .setKey(existingKey)
    .setExpectedRevision(existingRevision)  // Prevent concurrent update conflicts
    .setActor(RequestActor.newBuilder()
        .setOnBehalfOfUserId(userId)
        .build())
    .build();

try {
    memoriesStub.updateMemory(request);
} catch (StatusRuntimeException e) {
    if (e.getStatus().getCode() == Status.Code.ABORTED) {
        // Revision conflict - retry consolidation
        LOG.warnf("Revision conflict for memory %s, retrying", existingKey);
        // Reload and retry
    }
}
```

## Challenges

1. **Semantic Similarity**: How to detect "works at Acme" vs "employed by Acme Corp"?
2. **Contradiction Detection**: "prefers Go" vs "prefers Rust" - which is correct?
3. **Performance**: Searching existing memories for each candidate adds latency
4. **Concurrency**: Multiple processors updating same memory simultaneously
5. **Citation Merging**: How to combine citations from different sources?

## Alternative: Accept Duplicates

**Pros**:
- Simpler implementation
- Faster processing
- Natural history of memory evolution
- Search can rank by recency/confidence

**Cons**:
- Storage overhead
- Redundant search results
- Harder to maintain single source of truth

## Decision

**Phase 3**: Accept duplicates, defer consolidation to future phase.

**Rationale**:
- Core pipeline more important than deduplication
- Consolidation is complex and needs careful design
- Can be added later without breaking existing functionality
- Search ranking can mitigate duplicate issues

## Future Work

When implementing consolidation:

1. **Research semantic similarity approaches**:
   - Embedding-based similarity (cosine distance)
   - LLM-based equivalence checking
   - Exact content matching as fallback

2. **Design contradiction resolution**:
   - Timestamp-based (most recent wins)
   - Confidence-based (highest confidence wins)
   - User confirmation for critical facts

3. **Implement revision-aware updates**:
   - Use Enhancement 101's conditional writes
   - Handle ABORTED status with retry logic
   - Prevent lost updates from concurrent processors

4. **Add consolidation metrics**:
   - Duplicates detected
   - Memories merged
   - Conflicts resolved
   - Consolidation latency

5. **Test edge cases**:
   - Concurrent updates to same memory
   - Contradictory facts from different conversations
   - High-volume duplicate detection performance

## References

- Enhancement 101: Revision-aware memory writes
- Enhancement 100: Enhanced memory search (for similarity detection)
- Phase 3G: MemoryWriter implementation
