# Cognitive Process Management Framework

## Summary

Implemented Phase 1 of a cognitive process management framework for `cognition-processor-quarkus`.

This change introduces a minimal but extensible management layer for cognitive processes. The implementation is multi-process by design from the beginning, even though only one concrete process is currently registered: the durable memory extraction pipeline.

The framework adds a registry, manager, process contract, REST management endpoints, and a managed-process adapter for the existing durable extraction flow.

The current implementation has since been simplified from the original Phase 1 shape:

- process API responses no longer expose `supportsStart`, `supportsEnable`, or `supportsDisable`
- unsupported lifecycle operations now fail directly when invoked
- process state is currently reduced to registration-time intent with only `ENABLED` and `DISABLED`
- the `durable-memory-extraction` process is currently registered as `ENABLED`

## Motivation

Issue #7 requested a management interface for all cognitive processes with support for:

- list
- disable
- enable
- start
- inspect

The project already had one concrete cognitive process implemented in code, but no generic management abstraction around it. The goal of this change was to add a small management framework without forcing a redesign of the existing runtime pipeline.

## Design

### Process abstraction

A new `CognitiveProcess` interface defines the common contract for managed processes.

It exposes:

- identity and descriptive metadata
- current state
- inspection details
- optional lifecycle methods

Lifecycle methods remain optional. By default:

- `start()`
- `enable()`
- `disable()`

throw `UnsupportedOperationException`.

The interface still contains capability methods internally, but those capability flags are no longer exposed in the REST API payloads. Clients are expected to invoke lifecycle operations directly and handle an error response if the operation is unsupported.

### Minimal state model

The lifecycle state model is currently simplified to:

- `ENABLED`
- `DISABLED`

At this stage, state is intended to reflect registration-time configuration/intent rather than live runtime activity.

For the currently registered process:

- `durable-memory-extraction` reports `ENABLED`

### Registry and manager

The framework introduces:

- `CognitiveProcessRegistry`
- `CognitiveProcessManager`

The registry discovers and exposes all CDI-managed `CognitiveProcess` implementations.

The manager provides generic operations for:

- listing processes
- inspecting a process
- starting a process
- enabling a process
- disabling a process

The manager now delegates lifecycle operations directly to the process implementation without performing capability pre-checks first.

If a process does not support an operation, the process implementation fails through `UnsupportedOperationException`.

### REST API

A REST resource exposes generic management endpoints under:

- `GET /api/processes`
- `GET /api/processes/{id}`
- `POST /api/processes/{id}/start`
- `POST /api/processes/{id}/enable`
- `POST /api/processes/{id}/disable`

Unsupported operations are mapped to HTTP `501 Not Implemented`.

Unknown process IDs are mapped to HTTP `404 Not Found`.

### First managed process

The first concrete managed process is:

- `durable-memory-extraction`

This process wraps the existing durable memory extraction pipeline as one managed capability.

It reuses the existing runtime components rather than restructuring them.

The managed process currently supports:

- inspect
- start

It does not currently implement:

- enable
- disable

Those operations therefore return an error if invoked.

## Implementation Details

### New classes

Added under `io.github.rigazilla.memory.cognition.process`:

- `CognitiveProcess`
- `ManagedProcessState`
- `ManagedProcessInfo`
- `ManagedProcessInspection`
- `CognitiveProcessRegistry`
- `CognitiveProcessManager`
- `DurableMemoryExtractionProcess`
- `ProcessManagementResource`

### Existing code integration

Updated:

- `GrpcAdminEventClient`

Added:

- `startIfNeeded()`

This provides a minimal management-triggered startup hook so the managed process can implement `start()` without changing the existing startup behavior.

### Inspection data

The durable extraction process exposes inspection details derived from existing runtime components:

- `eventStreamConnected`
- `eventsAccepted`
- `activeWindows`
- `totalQueues`
- `activeQueues`
- `pendingJobs`

These values are computed at inspection time from live runtime state. They are not fixed registration-time metadata.

For example:

- `pendingJobs` is the current number of queued jobs waiting to be processed in the durable extraction pipeline

### API payload shape

The current API payload shape is intentionally minimal.

Summary responses include:

- `id`
- `displayName`
- `description`
- `state`

Inspection responses include:

- `id`
- `displayName`
- `description`
- `state`
- `details`

The earlier `supports*` response fields were removed to keep the API simpler. Clients should call the desired lifecycle endpoint and rely on the returned success or error response.

## Impact on Existing Code

This feature does not require a redesign of the current cognitive pipeline.

The existing runtime flow remains intact:

- `GrpcAdminEventClient`
- `DirtyWindowRegistry`
- `ScopeJobDispatcher`
- `JobQueueRegistry`
- `JobProcessor`

The new framework wraps the existing durable extraction flow behind a management abstraction.

Most of the implementation is new orchestration and API code rather than invasive changes to the runtime pipeline.

## Current Limitations

This implementation intentionally does not yet include:

- persistent process state
- richer lifecycle states such as `FAILED`, `STARTING`, or `STOPPING`
- pause, drain, restart, or stop semantics
- standardized health model
- dynamic plugin registration
- enable/disable support for the durable extraction process

Also, the current `state` field is intentionally coarse. It reflects configured availability (`ENABLED`/`DISABLED`) rather than detailed runtime execution status.

## Validation

The implementation was validated by compiling the project successfully:

```bash
./mvnw clean compile
```

Compilation completed successfully with exit code `0`.

## Files Added or Updated

### Added

- `src/main/java/io/github/rigazilla/memory/cognition/process/CognitiveProcess.java`
- `src/main/java/io/github/rigazilla/memory/cognition/process/ManagedProcessState.java`
- `src/main/java/io/github/rigazilla/memory/cognition/process/ManagedProcessInfo.java`
- `src/main/java/io/github/rigazilla/memory/cognition/process/ManagedProcessInspection.java`
- `src/main/java/io/github/rigazilla/memory/cognition/process/CognitiveProcessRegistry.java`
- `src/main/java/io/github/rigazilla/memory/cognition/process/CognitiveProcessManager.java`
- `src/main/java/io/github/rigazilla/memory/cognition/process/DurableMemoryExtractionProcess.java`
- `src/main/java/io/github/rigazilla/memory/cognition/process/ProcessManagementResource.java`

### Updated

- `src/main/java/io/github/rigazilla/memory/cognition/event/GrpcAdminEventClient.java`

## Follow-up Work

Likely next steps include:

- add persistent process state
- add more managed cognitive processes
- decide whether capability methods should remain internal or be removed entirely
- implement enable/disable for processes that can support it safely
- refine inspection payload usefulness
- standardize health and metrics reporting