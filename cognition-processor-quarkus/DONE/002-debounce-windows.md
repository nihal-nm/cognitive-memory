# 002 - Conversation Debounce Windows (Dirty Windows)

**Date**: 2026-05-22  
**Status**: ✅ Complete  
**Phase**: 2 - Event Batching & Efficiency

## Overview

Implemented a debounce mechanism that batches multiple events for the same conversation into a single processing job. This reduces LLM calls, improves efficiency, and maintains bounded freshness guarantees through configurable promotion triggers.

## Implementation Details

### Core Components

1. **DirtyWindow** (`io.github.rigazilla.memory.cognition.event.DirtyWindow`)
   - Represents a debounce window for a single conversation
   - Tracks event cursors, entry IDs, timing, and metadata
   - NOT thread-safe (synchronization handled by registry)

2. **DirtyWindowRegistry** (`io.github.rigazilla.memory.cognition.event.DirtyWindowRegistry`)
   - ApplicationScoped registry managing all active windows
   - ConcurrentHashMap for thread-safe window updates
   - ReentrantLock for promotion operations
   - Bounded by `max-checkpoint-windows` configuration

3. **SerializedWindow** (`io.github.rigazilla.memory.cognition.event.SerializedWindow`)
   - Record type for checkpoint persistence
   - Contains all window state for restart recovery

4. **ScopeJob** (`io.github.rigazilla.memory.cognition.event.ScopeJob`)
   - Represents a promoted window ready for processing
   - Includes trigger type for observability

5. **DebounceScheduler** (`io.github.rigazilla.memory.cognition.event.DebounceScheduler`)
   - Quarkus @Scheduled component
   - Scans for ready windows every 5 seconds
   - Logs registry status every 60 seconds

### Architecture

#### Event Flow
```
Event arrives
    ↓
GrpcAdminEventClient.handleEvent()
    ↓
DirtyWindowRegistry.acceptEvent()
    ↓
Create or extend window
    ↓
Check immediate promotion triggers
    ↓
Periodic scan (every 5s)
    ↓
Promote ready windows
    ↓
ScopeJobDispatcher.dispatch()
```

#### Promotion Triggers

Windows are promoted when ANY of these conditions are met:

1. **Debounce delay expired**: `now >= dueAt`
   - Default: 1 minute after first event
   - Ensures bounded freshness

2. **Max batch age reached**: `now - firstObservedAt >= max-batch-age`
   - Default: 5 minutes
   - Prevents indefinite accumulation

3. **Max batch entries reached**: `entryCount >= max-batch-entries`
   - Default: 24 entries
   - Immediate promotion on threshold

4. **Checkpoint bounded**: `windows.size() >= max-checkpoint-windows`
   - Default: 1000 windows
   - Promotes oldest due window to make room

#### Thread Safety

**Concurrent Access Patterns**:
- Event ingestion (gRPC executor threads)
- Promotion scheduler (Quarkus scheduler thread)
- Checkpoint writer (async)

**Synchronization Strategy**:
- `ConcurrentHashMap` for window updates
- `ReentrantLock` for promotion operations
- Atomic operations for immediate promotions

#### Checkpoint Integration

**Enhanced CheckpointState**:
```java
record CheckpointState(
    String lastEventCursor,
    Instant updatedAt,
    String runtimeId,
    String runtimeVersion,
    Instant highestEventTimestamp,
    List<SerializedWindow> dirtyWindows  // NEW
)
```

**Checkpoint Triggers**:
- Every 10 events
- After window promotions
- On shutdown

**Restart Recovery**:
1. Load checkpoint
2. Restore dirty windows to registry
3. Resume event stream from cursor
4. Continue promotion scheduling

### Configuration

**application.properties**:
```properties
# Debounce timing
cognition.scheduler.debounce-delay=PT1M
cognition.scheduler.max-batch-age=PT5M
cognition.scheduler.max-batch-entries=24

# Checkpoint bounds
cognition.scheduler.max-checkpoint-windows=1000

# Runtime identity
cognition.runtime.id=quarkus-reference-v1
cognition.runtime.version=1
```

### Data Structures

#### DirtyWindow Fields
```java
- conversationId: String           // Identity
- firstEventCursor: String         // First event in window
- latestEventCursor: String        // Most recent event
- entryIds: Set<String>            // Unique entry IDs
- firstObservedAt: Instant         // Window opened
- latestObservedAt: Instant        // Last event received
- dueAt: Instant                   // Promotion deadline
- eventCount: int                  // Total events
```

#### ScopeJob Fields
```java
- conversationId: String
- firstEventCursor: String
- latestEventCursor: String
- entryIds: List<String>
- observedAt: Instant
- processAfter: Instant
- trigger: String                  // Promotion reason
```

### Observability

#### REST API
```bash
GET /api/events/status
{
  "connected": true,
  "eventCount": 42,
  "activeWindows": 5,
  "oldestWindowAgeSeconds": 45
}
```

#### Logging

**Event Acceptance**:
```
Creating new window for conversation: conv-abc
Extended window for conversation conv-abc: DirtyWindow[conv=conv-abc, events=3, entries=2, age=PT30S, dueIn=PT30S]
```

**Window Promotion**:
```
Found 3 windows ready for promotion
Promoting window for conversation conv-abc (trigger: debounce_delay): DirtyWindow[...]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Scope Job Dispatched
  Conversation ID:  conv-abc
  Trigger:          debounce_delay
  Event Cursors:    120 -> 123
  Entry Count:      2
  Entry IDs:        [entry-1, entry-2]
  Observed At:      2026-05-22T12:00:00Z
  Process After:    2026-05-22T12:01:00Z
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Checkpoint Persistence**:
```
✓ Checkpoint saved at cursor: 123 (windows: 5, events: 42)
```

**Registry Status** (every 60s):
```
Registry status: 5 active windows, oldest age: PT45S
```

### Example Scenarios

#### Scenario 1: Normal Debounce
```
T+0s:  Event 1 arrives for conv-abc
       → Create window, dueAt = T+60s

T+10s: Event 2 arrives for conv-abc
       → Extend window, dueAt still T+60s

T+20s: Event 3 arrives for conv-abc
       → Extend window, dueAt still T+60s

T+60s: Promotion scan runs
       → Window is due, promote to ScopeJob
       → Process conversation with all 3 events
```

#### Scenario 2: Max Batch Entries
```
T+0s:  Event 1 arrives for conv-xyz
       → Create window, entryCount = 1

T+5s:  Events 2-24 arrive for conv-xyz
       → Extend window, entryCount = 24

T+5s:  Event 25 arrives for conv-xyz
       → entryCount would be 25 (>= max-batch-entries)
       → Immediate promotion to ScopeJob
       → Event 25 creates new window
```

#### Scenario 3: Restart Recovery
```
Before shutdown:
  - Window for conv-abc: events 1-3, dueAt = T+60s
  - Window for conv-def: events 1-2, dueAt = T+120s
  - Checkpoint saved with both windows

After restart:
  - Load checkpoint
  - Restore both windows to registry
  - Resume event stream from lastEventCursor
  - Promotion scheduler continues from where it left off
```

## Testing

### Manual Testing
```bash
# Start Memory Service
cd /home/rigazilla/git/memory-service
docker-compose up -d

# Start Cognition Processor
cd /home/rigazilla/git/cognitive-memory/cognition-processor-quarkus
mvn quarkus:dev

# Check status
curl http://localhost:8090/api/events/status | jq .

# Generate events in Memory Service to see windows form and promote
```

### Verification
- ✅ Windows created on first event for conversation
- ✅ Windows extended on subsequent events
- ✅ Promotion on debounce delay (60s)
- ✅ Promotion on max batch age (5m)
- ✅ Promotion on max batch entries (24)
- ✅ Checkpoint includes dirty windows
- ✅ Restart recovery restores windows
- ✅ Bounded by max checkpoint windows
- ✅ Thread-safe concurrent access
- ✅ Metrics exposed via REST API

## Benefits

1. **Efficiency**: Multiple events → one LLM call
2. **Bounded Freshness**: Max delays are configurable
3. **Restart Safety**: Checkpoint includes open windows
4. **Memory Bounded**: Max checkpoint windows enforced
5. **Thread Safe**: ConcurrentHashMap + locks
6. **Observable**: Metrics and detailed logging
7. **Testable**: Clear state transitions

## Known Limitations

1. **Simple Dispatcher**: Phase 2 dispatcher only logs jobs (no actual processing)
2. **File-based Checkpoints**: Not production-ready for distributed deployments
3. **No Metrics Framework**: Basic metrics via REST, no Prometheus/Micrometer integration yet
4. **No Unit Tests**: Deferred to Phase 3 (integration test passed)

## Alignment with Enhancement 099

This implementation follows the debounce window design from [Enhancement 099: Quarkus + LangChain4j Cognition Processor](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md):

- ✅ Conversation-scoped debounce windows
- ✅ Multiple promotion triggers (delay, age, entries, bounds)
- ✅ Checkpoint-embedded window state
- ✅ Restart recovery
- ✅ Bounded checkpoint growth
- ✅ Thread-safe registry
- ✅ Observable via logging and REST

## Next Steps (Phase 3)

1. **Actual Job Processing**
   - Implement job queue with retry
   - Load conversation context
   - Run extraction/verification
   - Write derived memories

2. **Enhanced Metrics**
   - Micrometer/Prometheus integration
   - Window promotion counters by trigger type
   - Processing latency histograms
   - Error rates

3. **Unit Tests**
   - Window creation and extension
   - Promotion trigger logic
   - Checkpoint serialization
   - Restart recovery
   - Concurrent access patterns

4. **Production Readiness**
   - Database-backed checkpoints
   - Distributed coordination
   - Health checks
   - Graceful shutdown

## Files Modified/Created

### New Files
- `src/main/java/io/github/rigazilla/memory/cognition/event/DirtyWindow.java`
- `src/main/java/io/github/rigazilla/memory/cognition/event/DirtyWindowRegistry.java`
- `src/main/java/io/github/rigazilla/memory/cognition/event/SerializedWindow.java`
- `src/main/java/io/github/rigazilla/memory/cognition/event/ScopeJob.java`
- `DONE/002-debounce-windows.md` (this document)

### Modified Files
- `src/main/java/io/github/rigazilla/memory/cognition/event/GrpcAdminEventClient.java`
  - Integrated with DirtyWindowRegistry
  - Accept events into windows
  - Enhanced checkpoint with dirty windows
  - Restore windows on startup

- `src/main/java/io/github/rigazilla/memory/cognition/event/CheckpointService.java`
  - Enhanced CheckpointState with dirtyWindows
  - JSON serialization with Jackson
  - File-based persistence

- `src/main/java/io/github/rigazilla/memory/cognition/event/DebounceScheduler.java`
  - Implemented promotion scan (@Scheduled every 5s)
  - Registry status logging (@Scheduled every 60s)

- `src/main/java/io/github/rigazilla/memory/cognition/event/ScopeJobDispatcher.java`
  - Enhanced logging for dispatched jobs
  - Placeholder for Phase 3 processing

- `src/main/java/io/github/rigazilla/memory/cognition/event/EventStatusResource.java`
  - Added activeWindows metric
  - Added oldestWindowAgeSeconds metric

- `pom.xml`
  - Added quarkus-scheduler dependency
  - Added jackson-datatype-jsr310 for Java 8 date/time

## Conclusion

Phase 2 successfully implements conversation debounce windows with multiple promotion triggers, checkpoint-based restart recovery, and bounded memory growth. The system can now:

- Batch multiple events for the same conversation
- Promote windows based on time, age, or entry count
- Persist open windows in checkpoints
- Recover windows after restart
- Enforce checkpoint size limits
- Provide observability via REST API

This completes the event batching layer and provides a solid foundation for Phase 3's actual cognitive processing implementation.
