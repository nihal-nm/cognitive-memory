# TODO: Job Retry Logic

**Priority**: MEDIUM  
**Status**: Future enhancement

## Problem

Failed jobs are logged but not retried. Transient failures (network errors, LLM timeouts, temporary service unavailability) result in lost processing.

## Current Behavior

```java
// In JobProcessor.processJob()
try {
    // ... pipeline stages ...
    LOG.infof("✓ Job completed successfully in %dms", duration);
} catch (Exception e) {
    LOG.errorf(e, "✗ Job failed after %dms", duration);
    // Job is discarded, no retry
}
```

## Impact

- Network blips cause permanent data loss
- LLM timeout → conversation never processed
- Temporary memory-service downtime → events lost
- No visibility into retry-able vs permanent failures

## Design

### Retry Strategy

1. **Exponential Backoff**: 1s, 2s, 4s, 8s, 16s, 32s (max 6 retries)
2. **Retry Queue**: Separate from main job queue
3. **Max Retries**: Configurable per job type
4. **Dead Letter Queue**: Failed jobs after max retries

### Implementation

```java
public class RetryableJob {
    private final ScopeJob originalJob;
    private final int attemptNumber;
    private final Instant nextRetryTime;
    private final Exception lastError;
    
    public RetryableJob(ScopeJob job, int attempt, Exception error) {
        this.originalJob = job;
        this.attemptNumber = attempt;
        this.lastError = error;
        this.nextRetryTime = calculateNextRetry(attempt);
    }
    
    private Instant calculateNextRetry(int attempt) {
        // Exponential backoff: 2^attempt seconds
        long delaySeconds = (long) Math.pow(2, attempt);
        return Instant.now().plusSeconds(delaySeconds);
    }
}

@ApplicationScoped
public class RetryQueue {
    private final PriorityQueue<RetryableJob> queue = 
        new PriorityQueue<>(Comparator.comparing(RetryableJob::nextRetryTime));
    
    private final Lock lock = new ReentrantLock();
    
    @ConfigProperty(name = "cognition.retry.max-attempts", defaultValue = "6")
    int maxRetries;
    
    public void enqueueRetry(ScopeJob job, Exception error, int currentAttempt) {
        if (currentAttempt >= maxRetries) {
            LOG.errorf("Job exceeded max retries (%d), moving to dead letter queue: %s", 
                maxRetries, job);
            deadLetterQueue.add(job, error);
            return;
        }
        
        lock.lock();
        try {
            RetryableJob retryJob = new RetryableJob(job, currentAttempt + 1, error);
            queue.offer(retryJob);
            LOG.infof("Scheduled retry #%d for job %s at %s", 
                currentAttempt + 1, job.conversationId(), retryJob.nextRetryTime());
        } finally {
            lock.unlock();
        }
    }
    
    public Optional<RetryableJob> pollReady() {
        lock.lock();
        try {
            RetryableJob next = queue.peek();
            if (next != null && next.nextRetryTime().isBefore(Instant.now())) {
                return Optional.of(queue.poll());
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }
}

@ApplicationScoped
public class DeadLetterQueue {
    private final List<FailedJob> failures = new CopyOnWriteArrayList<>();
    
    public void add(ScopeJob job, Exception error) {
        failures.add(new FailedJob(job, error, Instant.now()));
        LOG.errorf(error, "Job permanently failed: %s", job);
        
        // TODO: Persist to database for manual review
        // TODO: Send alert/notification
    }
    
    public List<FailedJob> getFailures() {
        return List.copyOf(failures);
    }
}
```

### Update JobProcessor

```java
@Inject
RetryQueue retryQueue;

@Inject
DeadLetterQueue deadLetterQueue;

private void processJob(ScopeJob job) {
    processJob(job, 0); // Start with attempt 0
}

private void processJob(ScopeJob job, int attemptNumber) {
    LOG.infof("▶ Processing job (attempt %d): %s", attemptNumber + 1, job);
    long startTime = System.currentTimeMillis();
    
    try {
        // ... pipeline stages ...
        LOG.infof("✓ Job completed successfully in %dms", duration);
        
    } catch (TransientException e) {
        // Retry-able error (network, timeout, etc.)
        LOG.warnf(e, "✗ Job failed with transient error after %dms, will retry", duration);
        retryQueue.enqueueRetry(job, e, attemptNumber);
        
    } catch (PermanentException e) {
        // Non-retry-able error (invalid data, authorization, etc.)
        LOG.errorf(e, "✗ Job failed with permanent error after %dms", duration);
        deadLetterQueue.add(job, e);
        
    } catch (Exception e) {
        // Unknown error - treat as transient
        LOG.errorf(e, "✗ Job failed with unknown error after %dms, will retry", duration);
        retryQueue.enqueueRetry(job, e, attemptNumber);
    }
}

@Scheduled(every = "1s")
void processRetries() {
    Optional<RetryableJob> retryJob = retryQueue.pollReady();
    retryJob.ifPresent(job -> {
        LOG.infof("Retrying job (attempt %d): %s", 
            job.attemptNumber(), job.originalJob().conversationId());
        processJob(job.originalJob(), job.attemptNumber());
    });
}
```

### Error Classification

```java
public class TransientException extends RuntimeException {
    public TransientException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class PermanentException extends RuntimeException {
    public PermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}

// In pipeline stages, classify errors:
try {
    // ... gRPC call ...
} catch (StatusRuntimeException e) {
    switch (e.getStatus().getCode()) {
        case UNAVAILABLE:
        case DEADLINE_EXCEEDED:
        case RESOURCE_EXHAUSTED:
            throw new TransientException("Service temporarily unavailable", e);
        
        case PERMISSION_DENIED:
        case INVALID_ARGUMENT:
        case NOT_FOUND:
            throw new PermanentException("Request cannot be retried", e);
        
        default:
            throw new TransientException("Unknown error, assuming transient", e);
    }
}
```

## Configuration

```properties
# Retry settings
cognition.retry.max-attempts=6
cognition.retry.initial-delay=PT1S
cognition.retry.max-delay=PT32S
cognition.retry.backoff-multiplier=2.0

# Dead letter queue
cognition.dlq.persist=true
cognition.dlq.alert-threshold=10
```

## Metrics

Track retry behavior:

```java
@Inject
MeterRegistry registry;

// In RetryQueue
Counter retryCounter = registry.counter("cognition.jobs.retried");
Counter dlqCounter = registry.counter("cognition.jobs.dead_letter");

// In JobProcessor
Timer processingTimer = registry.timer("cognition.jobs.processing_time");
Counter successCounter = registry.counter("cognition.jobs.success");
Counter failureCounter = registry.counter("cognition.jobs.failure");
```

## Testing

```java
@QuarkusTest
class RetryLogicTest {
    
    @Test
    void testTransientFailureRetries() {
        // Simulate network error
        // Verify job retried with exponential backoff
        // Verify success after retry
    }
    
    @Test
    void testPermanentFailureNoRetry() {
        // Simulate authorization error
        // Verify job goes to DLQ immediately
        // Verify no retry attempts
    }
    
    @Test
    void testMaxRetriesExceeded() {
        // Simulate persistent transient error
        // Verify 6 retry attempts
        // Verify job moves to DLQ after max retries
    }
}
```

## Future Enhancements

1. **Jitter**: Add random jitter to backoff to prevent thundering herd
2. **Circuit Breaker**: Stop retrying if service is consistently down
3. **Priority Retries**: Retry high-priority conversations first
4. **Manual Retry**: Admin API to retry DLQ jobs
5. **Persistent DLQ**: Store failed jobs in database for audit trail

## References

- Resilience4j: https://resilience4j.readme.io/docs/retry
- Quarkus Fault Tolerance: https://quarkus.io/guides/smallrye-fault-tolerance
