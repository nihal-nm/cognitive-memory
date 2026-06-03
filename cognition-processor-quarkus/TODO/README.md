# TODO: Remaining Work Items

This directory contains detailed documentation for remaining work items after Phase 6 completion.

## Quick Summary

### ✅ Recently Completed (Phase 4-6)

1. **Authentication & Authorization** → `../DONE/014-authentication-authorization.md`
   - ✅ Implemented RequestActor on-behalf-of authorization
   - ✅ TranscriptLoader now loads conversation metadata for proper authorization
   - ✅ End-to-end testing unblocked

2. **Conversation Metadata Integration** → `../DONE/015-conversation-metadata-integration.md`
   - ✅ JobProcessor loads real user IDs from conversation metadata
   - ✅ Memories now written to correct user namespaces
   - ✅ Multi-user scenarios properly supported

### MEDIUM Priority (Quality Improvements)

1. **Configuration Improvements** (`configuration-improvements.md`)
   - **Issue**: Quarkus logs warnings about unrecognized LangChain4j config keys
   - **Impact**: Named model settings may not be applied (temp, model-id, max-tokens)
   - **Solution**: Verify correct Quarkus LangChain4j named model syntax
   - **Status**: Non-blocking, warnings only

2. **Retry Logic** (`retry-logic.md`)
   - **Issue**: Failed jobs are logged but not retried
   - **Impact**: Transient failures result in lost processing
   - **Solution**: Implement exponential backoff retry with dead letter queue
   - **Status**: Future enhancement

3. **Testing** (`testing.md`)
   - **Issue**: No automated tests (unit or integration)
   - **Impact**: No regression protection, manual testing only
   - **Solution**: Add comprehensive test suite
   - **Status**: Future work

### LOW Priority (Deferred Features)

4. **Memory Consolidation** (`consolidation.md`)
   - **Issue**: Duplicate memories stored if same facts appear in multiple batches
   - **Impact**: Storage bloat, redundant search results
   - **Solution**: Implement deduplication with semantic similarity
   - **Status**: Intentionally deferred to future phase

5. **Profile Context Snapshots** (`profile-context-snapshots.md`)
   - **Issue**: Agents need compact initial user context, not only raw memory search results
   - **Impact**: Repeated context gathering and weaker continuity across sessions
   - **Solution**: Nightly consolidation into a governed profile context memory
   - **Status**: Future enhancement

## Implementation Order

### Phase 7 (Next Steps - Quality Improvements)
1. Verify configuration improvements (MEDIUM)
2. Add retry logic (MEDIUM)
3. Add test suite (MEDIUM)

### Phase 8 (Future Features)
4. Implement consolidation (LOW)
5. Implement profile context snapshots (MEDIUM)

## Quick Links

### Recent Completions
- **Phase 6**: `../DONE/013-conversation-metadata-grpc-migration.md`
- **Phase 5**: `../DONE/012-grpc-admin-api-migration.md`
- **Phase 4**: `../DONE/011-window-linking-server-side-filtering.md`

### Earlier Phases
- **Phase 3**: `../DONE/003-job-processing-pipeline.md`
- **Phase 2**: `../DONE/002-debounce-windows.md`
- **Phase 1**: `../DONE/001-event-subscription.md`

## Current Limitations

1. ✅ ~~**Authorization gap**~~: FIXED - Processor uses RequestActor on-behalf-of authorization
2. ✅ ~~**User ID placeholder**~~: FIXED - Real user IDs loaded from conversation metadata
3. ⚠️ **No consolidation**: Duplicate memories will be stored (intentional)
4. ⚠️ **No retry logic**: Failed jobs are logged but not retried
5. ⚠️ **Named model config warnings**: LangChain4j config syntax may need adjustment
6. ⚠️ **No profile context snapshot**: Agents must assemble initial user context from raw memories

## Testing Status

- ✅ Manual end-to-end testing complete
- ✅ Event stream connection verified
- ✅ Debounce window promotion verified
- ✅ Full pipeline working (auth + metadata integration complete)
- ✅ Memories written to correct user namespaces
- ❌ No automated tests

## Next Session Actions

1. Verify and fix LangChain4j configuration warnings
2. Consider implementing retry logic for transient failures
3. Add comprehensive test suite (unit + integration)
4. Evaluate memory consolidation/deduplication approach
5. Design profile context snapshot extraction and nightly consolidation
