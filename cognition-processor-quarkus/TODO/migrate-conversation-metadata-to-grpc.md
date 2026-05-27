# Migrate Conversation Metadata to gRPC AdminConversationsService

## Status

**Blocked** - Waiting for memory-service PR to be merged

## Dependencies

- **memory-service PR #197**: https://github.com/chirino/memory-service/pull/197
- This PR adds `AdminConversationsService` with `GetConversation` method to gRPC API
- Currently the only admin endpoint for conversation metadata is REST-based

## Problem

Currently, conversation metadata loading uses REST while everything else uses gRPC:

**Current API usage:**
- ✅ gRPC: `AdminEventsService.StreamEvents` (event streaming)
- ✅ gRPC: `AdminEntriesService.ListEntries` (entry loading)
- ✅ gRPC: `MemoriesService.PutMemory` (memory writing)
- ❌ REST: `GET /v1/admin/conversations/{id}` (conversation metadata)

This creates API inconsistency and requires:
- Maintaining HTTP client code
- JSON parsing with Jackson
- Different error handling (HTTP status codes vs gRPC status codes)

## Goal

Achieve **100% gRPC API usage** by migrating conversation metadata loading to `AdminConversationsService.GetConversation`.

## Implementation Steps

### 1. Update memory-service dependency

Once PR #197 is merged and released:

```xml
<!-- In pom.xml - update to version that includes AdminConversationsService -->
<dependency>
    <groupId>io.github.chirino.memory</groupId>
    <artifactId>memory-service-contracts</artifactId>
    <version>NEW_VERSION</version> <!-- Update this -->
</dependency>
```

Rebuild to regenerate protobuf sources:
```bash
./mvnw clean compile
```

Verify the new service is available:
```bash
find target/generated-sources -name "*AdminConversationsService*"
```

### 2. Update JobProcessor imports

**File:** `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`

**Add imports:**
```java
import io.github.chirino.memory.grpc.v1.AdminConversationsServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminGetConversationRequest;
// Remove: com.fasterxml.jackson.databind.ObjectMapper and related imports
```

### 3. Replace conversationsStub initialization

**In JobProcessor.init():**

**Current:**
```java
conversationsStub = ConversationsServiceGrpc.newBlockingStub(channel);
```

**Replace with:**
```java
conversationsStub = AdminConversationsServiceGrpc.newBlockingStub(channel);
```

**Update field type:**
```java
// Change from:
private ConversationsServiceGrpc.ConversationsServiceBlockingStub conversationsStub;

// To:
private AdminConversationsServiceGrpc.AdminConversationsServiceBlockingStub conversationsStub;
```

### 4. Replace getConversationOwner() method

**Current implementation** (REST-based, ~60 lines):
```java
private String getConversationOwner(String conversationId) {
    try {
        // HTTP client setup
        String url = String.format("http://%s:%d/v1/admin/conversations/%s", ...);
        java.net.http.HttpClient client = ...;
        java.net.http.HttpRequest request = ...;
        
        // HTTP call
        java.net.http.HttpResponse<String> response = client.send(request, ...);
        
        // JSON parsing
        com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(response.body());
        String ownerId = json.get("ownerUserId").asText();
        
        return ownerId;
    } catch (Exception e) {
        throw new JobProcessingException(...);
    }
}
```

**New implementation** (gRPC-based, ~25 lines):
```java
private String getConversationOwner(String conversationId) {
    try {
        ByteString conversationIdBytes = uuidToBytes(conversationId);

        AdminGetConversationRequest request = AdminGetConversationRequest.newBuilder()
            .setConversationId(conversationIdBytes)
            .build();

        Conversation conversation = conversationsStub.getConversation(request);

        String ownerId = conversation.getOwnerUserId();
        LOG.debugf("Loaded conversation %s owner: %s", conversationId, ownerId);

        return ownerId;

    } catch (StatusRuntimeException e) {
        Status status = e.getStatus();
        LOG.errorf(e, "Failed to load conversation metadata for %s: %s", conversationId, status);
        throw new JobProcessingException("Failed to load conversation metadata for " + conversationId, e);
    } catch (Exception e) {
        LOG.errorf(e, "Unexpected error loading conversation metadata for %s", conversationId);
        throw new JobProcessingException("Failed to load conversation metadata for " + conversationId, e);
    }
}
```

### 5. Remove unused dependencies

**If ObjectMapper is only used for conversation metadata:**

Check if it's used elsewhere:
```bash
grep -n "objectMapper" src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java
```

If only used for conversation metadata, remove:
```java
// Remove this field if no longer needed:
private final ObjectMapper objectMapper = new ObjectMapper();
```

**Note:** ObjectMapper is still used for serializing memory candidates to JSON for verification, so it should be kept.

### 6. Update JavaDoc

Update the comment on `getConversationOwner()`:

```java
/**
 * Get the owner user ID for a conversation by loading conversation metadata via gRPC.
 * Uses AdminConversationsService which provides admin access without requiring membership.
 *
 * @param conversationId Conversation UUID string
 * @return Owner user ID
 * @throws JobProcessingException if conversation metadata cannot be loaded
 */
```

### 7. Test the changes

**Restart the application:**
```bash
./mvnw quarkus:dev
```

**Verify in logs:**
- No HTTP client messages
- gRPC call succeeds
- Conversation owner loaded correctly
- No PERMISSION_DENIED errors

**Look for:**
```
[0/5] Loading conversation metadata: <conversation-id>
✓ Conversation owner: <username>
```

### 8. Update documentation

**Update DONE/012-grpc-admin-api-migration.md:**
- Change status from "REST fallback required" to "100% gRPC"
- Update API coverage section (4/4 operations now gRPC)
- Move "Future Improvements" to "Completed"
- Add note about PR #197 merge

**Create new DONE document:**
- `013-conversation-metadata-grpc-migration.md`
- Document the migration from REST to gRPC
- Reference PR #197
- Before/after code comparison
- Benefits (code simplification, consistency)

## Benefits

1. **100% gRPC API usage** - Complete consistency
2. **Simpler code** - ~60 lines → ~25 lines
3. **Better performance** - Binary protocol instead of JSON/HTTP
4. **Type safety** - Protobuf messages instead of JSON parsing
5. **Consistent error handling** - gRPC StatusRuntimeException throughout
6. **No HTTP client** - One less dependency/code path

## Verification Checklist

After implementing:
- [ ] Code compiles without errors
- [ ] No HTTP client code remains in conversation loading
- [ ] gRPC stub uses `AdminConversationsServiceGrpc`
- [ ] Request type is `AdminGetConversationRequest`
- [ ] Logs show successful conversation owner loading
- [ ] No PERMISSION_DENIED errors
- [ ] Integration test passes (trigger job, verify memories written)
- [ ] Documentation updated (DONE/012, new DONE/013)

## Notes

- The migration is straightforward once PR #197 is merged
- Expected to reduce code by ~35 lines in JobProcessor
- No configuration changes needed (same host/port/credentials)
- Existing authentication interceptor works for admin service
- Should be backward compatible (memory-service version upgrade only)

## Related

- **Current implementation**: `DONE/012-grpc-admin-api-migration.md`
- **PR**: https://github.com/chirino/memory-service/pull/197
- **File to modify**: `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`
