# 012 - gRPC Admin API Migration

**Status**: ✅ Complete  
**Date**: 2026-05-27  
**Related**: Permission handling, API consistency

## Problem

The initial implementation used a mix of gRPC and REST APIs to communicate with memory-service:
- Event streaming via gRPC (`AdminEventsService`)
- Conversation metadata via REST (`GET /v1/admin/conversations/{id}`)
- Entry loading via gRPC regular service (`EntriesService`)
- Memory writing via gRPC (`MemoriesService`)

This caused two issues:
1. **Permission errors**: Regular `EntriesService.ListEntries` requires conversation membership, which the admin API key didn't have
2. **API inconsistency**: Mixed gRPC and REST made the codebase harder to maintain

## Solution

Migrated to use **Admin gRPC services** wherever available to maximize gRPC usage and leverage admin permissions.

### Current API Usage

#### gRPC Services (3 operations)

**1. AdminEventsService.StreamEvents** (`GrpcAdminEventClient`)
- **Purpose**: Subscribe to memory-service event stream
- **Authentication**: Admin API key via gRPC metadata headers (`x-api-key`, `authorization`)
- **Role**: `admin` role grants access to global event stream
- **Location**: `io.github.rigazilla.memory.cognition.event.GrpcAdminEventClient`

**2. AdminEntriesService.ListEntries** (`TranscriptLoader`)
- **Purpose**: Load conversation transcript entries
- **Authentication**: Admin API key via gRPC metadata headers
- **Role**: `admin` role bypasses conversation membership checks
- **Request type**: `AdminListEntriesRequest` (not regular `ListEntriesRequest`)
- **Location**: `io.github.rigazilla.memory.cognition.evidence.TranscriptLoader`
- **Key change**: Switched from regular `EntriesService` to `AdminEntriesService` to avoid PERMISSION_DENIED errors

**3. MemoriesService.PutMemory** (`MemoryWriter`)
- **Purpose**: Write extracted memories to memory-service
- **Authentication**: Admin API key via gRPC metadata headers
- **Role**: `admin` role allows writing to any namespace
- **Location**: `io.github.rigazilla.memory.cognition.writer.MemoryWriter`

#### REST Endpoint (1 operation)

**4. GET /v1/admin/conversations/{id}** (`JobProcessor`)
- **Purpose**: Load conversation metadata to get owner user ID
- **Authentication**: Admin API key via HTTP headers (`Authorization: Bearer`, `x-api-key`, `x-client-id`)
- **Role**: `admin` role grants access to admin REST endpoints
- **Location**: `io.github.rigazilla.memory.cognition.queue.JobProcessor.getConversationOwner()`
- **Why REST?**: No `AdminConversationsService` exists in gRPC (only `AdminEntriesService` and `AdminCheckpointService`)
- **Response format**: JSON with `ownerUserId` field (camelCase)

### Authentication Pattern

All services use a consistent authentication interceptor that adds metadata headers:

**gRPC Headers:**
```java
headers.put("x-api-key", apiKey);
headers.put("authorization", "Bearer " + apiKey);
headers.put("x-client-id", clientId);  // JobProcessor only
```

**REST Headers:**
```java
Authorization: Bearer {apiKey}
x-api-key: {apiKey}
x-client-id: {clientId}
Content-Type: application/json
```

### Admin Role Capabilities

The `admin` API key provides:
- ✅ Global event stream access (all conversations)
- ✅ Read any conversation's entries (bypass membership)
- ✅ Read any conversation's metadata
- ✅ Write memories to any namespace
- ✅ Checkpoint management

This enables the cognition processor to act as a system-wide indexer without requiring per-conversation authorization.

## Implementation Details

### TranscriptLoader Migration

**Before:**
```java
import io.github.chirino.memory.grpc.v1.EntriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.ListEntriesRequest;

private EntriesServiceGrpc.EntriesServiceBlockingStub entriesStub;

// Failed with PERMISSION_DENIED
ListEntriesRequest request = ListEntriesRequest.newBuilder()...
ListEntriesResponse response = entriesStub.listEntries(request.build());
```

**After:**
```java
import io.github.chirino.memory.grpc.v1.AdminEntriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminListEntriesRequest;

private AdminEntriesServiceGrpc.AdminEntriesServiceBlockingStub entriesStub;

// Works with admin credentials
AdminListEntriesRequest request = AdminListEntriesRequest.newBuilder()...
ListEntriesResponse response = entriesStub.listEntries(request.build());
```

### JobProcessor Conversation Metadata

**Attempted gRPC approach:**
```java
// Tried to migrate to gRPC but failed - no AdminConversationsService exists
import io.github.chirino.memory.grpc.v1.ConversationsServiceGrpc;

conversationsStub.getConversation(request);  // PERMISSION_DENIED
```

**Current REST approach:**
```java
// Only admin endpoint available for conversation metadata
GET http://localhost:8082/v1/admin/conversations/{id}
Response: {"id":"...","ownerUserId":"charlie",...}
```

### Field Name Discovery

During implementation, we discovered the conversation metadata JSON response uses **camelCase** field names:
- ✅ `ownerUserId` (correct)
- ❌ `owner_user_id` (incorrect - initial attempt)

The code now correctly accesses `json.get("ownerUserId")`.

## Configuration

### Required Properties

```properties
# gRPC connection (used by all services)
memory-service.grpc.host=localhost
memory-service.grpc.port=8082

# Admin API key (grants admin role permissions)
memory-service.api-key=admin-api-key-1

# Client ID (matches API key's client_id in memory-service)
memory-service.client-id=admin

# Worker identity for provenance
cognition.worker.id=admin
cognition.runtime.id=cognition-processor-v1
cognition.runtime.version=1.0.0-SNAPSHOT
```

### Memory Service Setup

The admin API key must be configured in memory-service with:
- Role: `admin`
- Client ID: matches `memory-service.client-id` property
- Permissions: `admin` role grants access to all Admin services

## API Coverage

**gRPC Admin Services:**
- ✅ `AdminEventsService` - used for event streaming
- ✅ `AdminEntriesService` - used for entry loading
- ✅ `AdminCheckpointService` - used for checkpointing (in `CheckpointService`)
- ❌ `AdminConversationsService` - **does not exist** (REST fallback required)

**Result:** 75% gRPC coverage (3 out of 4 operations)

## Future Improvements

### Option 1: Add AdminConversationsService to memory-service

If memory-service adds `AdminConversationsService` to gRPC, we can migrate the last REST call:

```java
// Future gRPC approach (when available)
import io.github.chirino.memory.grpc.v1.AdminConversationsServiceGrpc;

AdminGetConversationRequest request = AdminGetConversationRequest.newBuilder()
    .setConversationId(conversationIdBytes)
    .build();

Conversation conversation = adminConversationsStub.getConversation(request);
String ownerId = conversation.getOwnerUserId();
```

This would achieve **100% gRPC** usage.

### Option 2: Cache Conversation Metadata

Since conversation owner rarely changes, we could cache the owner ID:
- First lookup: REST call
- Subsequent lookups: in-memory cache
- Cache invalidation: on conversation update events

This would reduce REST calls to ~1 per conversation.

## Testing

The implementation was tested and verified working:

**Test conversation:**
- ID: `a4bb4523-82f6-43be-a78c-641cb1588cd2`
- Owner: `charlie`
- Entries: 20 transcript entries successfully loaded

**Logs show successful operation:**
```
[0/5] Loading conversation metadata: a4bb4523-82f6-43be-a78c-641cb1588cd2
✓ Conversation owner: charlie
[1/5] Loading transcript for conversation: a4bb4523-82f6-43be-a78c-641cb1588cd2
✓ Loaded 20 transcript entries
[2/5] Extracting memories from evidence
```

No permission errors encountered.

## Related Files

**Modified:**
- `src/main/java/io/github/rigazilla/memory/cognition/evidence/TranscriptLoader.java`
  - Changed: `EntriesService` → `AdminEntriesService`
  - Changed: `ListEntriesRequest` → `AdminListEntriesRequest`
  - Removed: `ActorInterceptor` (on-behalf-of no longer needed)

- `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`
  - Kept: REST call for conversation metadata (no gRPC admin equivalent)
  - Fixed: Field name `owner_user_id` → `ownerUserId`

**Unchanged:**
- `src/main/java/io/github/rigazilla/memory/cognition/event/GrpcAdminEventClient.java` (already using admin service)
- `src/main/java/io/github/rigazilla/memory/cognition/writer/MemoryWriter.java` (already using admin-capable service)

## Benefits

1. **Consistency**: Maximized gRPC usage (3/4 operations)
2. **Permissions**: Admin services bypass membership checks
3. **Performance**: gRPC binary protocol for heavy operations (events, entries, memories)
4. **Simplicity**: Single authentication pattern across all services
5. **Correctness**: Eliminated PERMISSION_DENIED errors

## Trade-offs

**Pros:**
- Admin role provides system-wide access without per-conversation auth
- gRPC is more efficient for streaming and frequent calls
- Consistent API pattern (except for one REST call)

**Cons:**
- One REST call remains (conversation metadata)
- Admin credentials are powerful (require secure handling)
- Mixed API types (though minimized)

Overall, this is the optimal solution given memory-service's current API surface. The cognition processor now has reliable, performant access to all required data.
