# TODO: Remaining Work Items

This directory contains detailed documentation for remaining work items after Phase 3 completion.

## Quick Summary

### HIGH Priority (Blocking Production)

1. **Authentication & Authorization** (`authentication-authorization.md`)
   - **Issue**: TranscriptLoader fails with PERMISSION_DENIED when loading conversation entries
   - **Root Cause**: ListEntries is membership-scoped, admin credentials don't bypass membership checks
   - **Solution**: Implement RequestActor on-behalf-of authorization
   - **Status**: Blocking end-to-end testing

2. **Conversation Metadata Integration** (`conversation-metadata.md`)
   - **Issue**: JobProcessor uses hardcoded "user-placeholder" as userId
   - **Impact**: All memories written to wrong namespace
   - **Solution**: Load conversation metadata to get real owner user ID
   - **Status**: Blocking proper memory scoping

### MEDIUM Priority (Quality Improvements)

3. **Configuration Improvements** (`configuration-improvements.md`)
   - **Issue**: Quarkus logs warnings about unrecognized LangChain4j config keys
   - **Impact**: Named model settings may not be applied (temp, model-id, max-tokens)
   - **Solution**: Verify correct Quarkus LangChain4j named model syntax
   - **Status**: Non-blocking, warnings only

4. **Retry Logic** (`retry-logic.md`)
   - **Issue**: Failed jobs are logged but not retried
   - **Impact**: Transient failures result in lost processing
   - **Solution**: Implement exponential backoff retry with dead letter queue
   - **Status**: Future enhancement

5. **Testing** (`testing.md`)
   - **Issue**: No automated tests (unit or integration)
   - **Impact**: No regression protection, manual testing only
   - **Solution**: Add comprehensive test suite
   - **Status**: Future work

### LOW Priority (Deferred Features)

6. **Memory Consolidation** (`consolidation.md`)
   - **Issue**: Duplicate memories stored if same facts appear in multiple batches
   - **Impact**: Storage bloat, redundant search results
   - **Solution**: Implement deduplication with semantic similarity
   - **Status**: Intentionally deferred to future phase

## Implementation Order

### Phase 4 (Next Steps)
1. Fix authentication/authorization (HIGH)
2. Fix conversation metadata integration (HIGH)
3. Verify configuration improvements (MEDIUM)

### Phase 5 (Quality)
4. Add retry logic (MEDIUM)
5. Add test suite (MEDIUM)

### Phase 6 (Features)
6. Implement consolidation (LOW)

## Quick Links

- **Phase 3 Completion**: `../DONE/003-job-processing-pipeline.md`
- **Phase 2 Completion**: `../DONE/002-debounce-windows.md`
- **Phase 1 Completion**: `../DONE/001-event-subscription.md`

## Current Limitations

From `DONE/003-job-processing-pipeline.md`:

1. ❌ **Authorization gap**: Processor cannot read conversation entries without membership or on-behalf-of
2. ❌ **User ID placeholder**: All memories written to `["user", "user-placeholder", "cognition.v1", *]`
3. ⚠️ **No consolidation**: Duplicate memories will be stored (intentional)
4. ⚠️ **No retry logic**: Failed jobs are logged but not retried
5. ⚠️ **Named model config warnings**: LangChain4j config syntax may need adjustment

## Testing Status

- ✅ Manual end-to-end testing (partial - blocked by auth)
- ✅ Event stream connection verified
- ✅ Debounce window promotion verified
- ❌ Full pipeline blocked by authorization issue
- ❌ No automated tests

## Next Session Actions

1. Choose authorization approach (Option 1, 2, or 3 from `authentication-authorization.md`)
2. Implement chosen solution
3. Test end-to-end pipeline with real Ollama
4. Verify memories written to correct namespace
5. Consider adding basic retry logic
6. Consider adding smoke tests
