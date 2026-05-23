# TODO: Event Stream Invalidation Handling

**Priority**: MEDIUM  
**Status**: Observed but not handled

## Problem

When the cognition processor restarts after being offline for an extended period, the checkpoint cursor may be older than the event stream's retention window. Memory-service sends an "invalidate" event to signal this:

```
Event Type:  invalidate
Kind:        stream
Reason:      cursor beyond retention window
```

## Current Behavior

`GrpcAdminEventClient.handleEvent()` logs the invalidate event but does not take any special action:
- Continues processing subsequent events
- Does not reset checkpoint
- Does not clear dirty windows
- May have missed events between old checkpoint and retention boundary

## Impact

**Data Loss Risk**: Events that occurred between the old checkpoint cursor and the retention window boundary are permanently lost. The processor will never see these events.

**Example Timeline**:
1. Processor checkpoints at cursor 100, then stops
2. Memory-service retention window is 1000 events
3. Processor offline while 2000 new events occur
4. Processor restarts, tries to resume from cursor 100
5. Memory-service sends "invalidate" (cursor 100 is beyond retention)
6. Events 100-1100 are lost (never processed)
7. Processor continues from cursor 1101+

## Solution

### Option 1: Reset and Start Fresh (Simple)

When receiving an "invalidate" event:
1. Clear all dirty windows
2. Delete checkpoint file
3. Reconnect with `afterCursor = null` (start from beginning)
4. Log warning about potential data loss

```java
private void handleEvent(EventNotification event) {
    String eventType = event.getEvent();
    
    // Handle invalidation
    if ("invalidate".equals(eventType)) {
        handleInvalidation(event);
        return;
    }
    
    // ... rest of event handling ...
}

private void handleInvalidation(EventNotification event) {
    String reason = extractJsonField(event.getData().toStringUtf8(), "reason");
    LOG.warnf("Event stream invalidated: %s", reason);
    
    if ("cursor beyond retention window".equals(reason)) {
        LOG.warn("Checkpoint cursor is too old, resetting to start from beginning");
        
        // Clear state
        windowRegistry.clearAllWindows();
        checkpointService.deleteCheckpoint(workerId);
        lastEventCursor = null;
        
        // Reconnect from beginning
        disconnect();
        connect();
    }
}
```

### Option 2: Backfill from Conversation History (Complex)

When receiving an "invalidate" event:
1. Query memory-service for all conversations modified since old checkpoint
2. For each conversation, load full entry history
3. Rebuild dirty windows from historical data
4. Continue processing from current cursor

**Pros**: No data loss  
**Cons**: Complex, expensive, may overwhelm system

### Option 3: Accept Data Loss with Monitoring (Pragmatic)

1. Log invalidation events with high severity
2. Emit metrics for monitoring/alerting
3. Document that processors should not be offline longer than retention window
4. Operational guidance: restart processors regularly to avoid invalidation

## Recommended Approach

**Phase 1** (immediate): Implement Option 1 (reset and start fresh)
- Simple, safe, prevents stale state
- Acceptable for development/testing
- Document data loss risk

**Phase 2** (production): Add monitoring and operational guidance
- Alert when invalidation occurs
- SLA: processors must restart within retention window
- Consider increasing retention window in memory-service

**Phase 3** (future): Consider Option 2 if data loss is unacceptable
- Requires conversation history API
- May need rate limiting to avoid overwhelming system

## Testing

```bash
# Simulate invalidation scenario
1. Start processor, let it checkpoint
2. Stop processor
3. Generate > retention window events in memory-service
4. Restart processor
5. Verify invalidation event received
6. Verify processor handles it gracefully
```

## Configuration

Add retention window awareness:
```properties
# Event stream retention (for monitoring)
cognition.event-stream.retention-window=1000
cognition.event-stream.max-offline-duration=PT1H

# Alert if offline longer than safe threshold
cognition.monitoring.offline-alert-threshold=PT45M
```

## Related Issues

- Checkpoint persistence (see `DONE/001-event-subscription.md`)
- Dirty window serialization (see `DONE/002-debounce-windows.md`)
- Operational monitoring (future work)

## References

- Enhancement 099: Event stream subscription and checkpointing
- Memory-service event stream retention policy
