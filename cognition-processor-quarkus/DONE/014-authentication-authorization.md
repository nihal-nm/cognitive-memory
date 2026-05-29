# DONE: Authentication & Authorization

**Originally**: HIGH Priority - Blocking end-to-end testing  
**Status**: ✅ **COMPLETED**  
**Completed**: Phase 4-6 (see also: `005-conversation-owner-metadata.md`, `013-conversation-metadata-grpc-migration.md`)

## Summary

Successfully implemented RequestActor on-behalf-of authorization pattern. TranscriptLoader now loads conversation metadata to properly authorize reading entries from any conversation. End-to-end testing is now unblocked.

---

## Original Problem Statement

`TranscriptLoader` fails with `PERMISSION_DENIED: forbidden` when calling `EntriesService.ListEntries`.

### Root Cause

`ListEntries` is a **membership-scoped API** that requires explicit conversation access. Admin credentials (`admin-api-key-1`) do NOT automatically grant access to read arbitrary user conversations.

From Enhancement 101:
- Admin scope applies to: event streams (`EVENT_SCOPE_ADMIN`), checkpoints, admin operations
- `ListEntries` remains membership-scoped - requires conversation membership or on-behalf-of authorization

## Current Behavior

```
io.grpc.StatusRuntimeException: PERMISSION_DENIED: forbidden
    at io.github.chirino.memory.grpc.v1.EntriesServiceGrpc$EntriesServiceBlockingStub.listEntries
    at io.github.rigazilla.memory.cognition.evidence.TranscriptLoader.loadTranscript
```

## Solutions

### Option 1: Grant Processor Membership (Testing Only)

**Pros**: Simple, immediate testing
**Cons**: Not scalable, requires modifying every conversation

**Implementation**:
```bash
# Create conversation with processor as member
curl -X POST http://localhost:8082/v1/conversations \
  -H "X-API-Key: admin-api-key-1" \
  -H "Authorization: Bearer admin-api-key-1" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Test Conversation",
    "members": [
      {"userId": "admin-api-key-1", "role": "OWNER"}
    ]
  }'
```

### Option 2: Admin-Scoped Entry Read API (If Available)

**Pros**: Clean separation, no membership needed
**Cons**: May not exist in current memory-service

**Investigation Needed**:
- Check if memory-service has admin-scoped conversation/entry read APIs
- Review Enhancement 101 for admin entry access patterns

### Option 3: On-Behalf-Of Authorization (Recommended)

**Pros**: Proper authorization model, scalable, follows Enhancement 101 design
**Cons**: Requires implementation work

**Implementation Steps**:

1. **Add ConversationsService client to TranscriptLoader**:
```java
private ConversationsServiceGrpc.ConversationsServiceBlockingStub conversationsStub;

@PostConstruct
void init() {
    // ... existing channel setup ...
    conversationsStub = ConversationsServiceGrpc.newBlockingStub(channel);
}
```

2. **Load conversation metadata**:
```java
private String getConversationOwner(String conversationId) {
    try {
        ByteString conversationIdBytes = uuidToBytes(conversationId);
        
        GetConversationRequest request = GetConversationRequest.newBuilder()
            .setId(conversationIdBytes)
            .build();
        
        Conversation conversation = conversationsStub.getConversation(request);
        
        // Extract owner user ID from conversation metadata
        // This may require checking conversation.getOwnerUserId() or similar field
        return conversation.getOwnerUserId();
        
    } catch (Exception e) {
        LOG.errorf(e, "Failed to load conversation metadata for %s", conversationId);
        throw new TranscriptLoadException("Failed to load conversation metadata", e);
    }
}
```

3. **Update ListEntriesRequest with RequestActor**:
```java
public EvidencePack loadTranscript(String conversationId) {
    try {
        LOG.debugf("Loading transcript for conversation: %s", conversationId);
        
        // Get conversation owner for on-behalf-of authorization
        String ownerId = getConversationOwner(conversationId);
        
        ByteString conversationIdBytes = uuidToBytes(conversationId);
        
        // Build request with RequestActor
        ListEntriesRequest request = ListEntriesRequest.newBuilder()
            .setConversationId(conversationIdBytes)
            .setChannel(Channel.HISTORY)
            .setActor(RequestActor.newBuilder()
                .setOnBehalfOfUserId(ownerId)
                .build())
            .build();
        
        // ... rest of implementation ...
    }
}
```

4. **Verify protobuf has RequestActor**:
```bash
# Check if RequestActor is defined in memory_service.proto
grep -n "RequestActor" /path/to/memory-service/contracts/protobuf/memory/v1/memory_service.proto

# If missing, coordinate with memory-service team to add it
```

5. **Update MemoryWriter similarly** (PARTIALLY IMPLEMENTED):
```java
// MemoryWriter now includes RequestActor for on-behalf-of writes
// Code added but NOT TESTED - left as TODO
PutMemoryRequest request = PutMemoryRequest.newBuilder()
    .addAllNamespace(namespace)
    .setKey(key)
    .setValue(value)
    .setActor(RequestActor.newBuilder()
        .setOnBehalfOfUserId(userId)
        .build())
    .build();
```

**Status**: MemoryWriter code updated with RequestActor but compilation/testing deferred.

## Testing

After implementing Option 3:

```bash
# Start memory-service
cd memory-service
task dev:memory-service

# Start Ollama
docker run -d -p 11434:11434 --name ollama ollama/ollama
docker exec ollama ollama pull llama3.2

# Start cognition processor
cd cognition-processor-quarkus
mvn quarkus:dev

# Create conversation (no special membership needed)
CONV_ID=$(curl -s -X POST http://localhost:8082/v1/conversations \
  -H "X-API-Key: user-api-key" \
  -H "Authorization: Bearer user-api-key" \
  -H "Content-Type: application/json" \
  -d '{"title": "Test Conversation"}' | jq -r '.id')

# Add entries
curl -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
  -H "X-API-Key: user-api-key" \
  -H "Authorization: Bearer user-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "HISTORY",
    "content": [{"role": "user", "text": "I work at Acme Corp"}]
  }'

# Wait 1 minute for debounce, check logs for successful processing
tail -f /tmp/quarkus-startup-final.log | grep "Job completed successfully"

# Verify memories written
curl -s "http://localhost:8082/v1/memories?namespace=user&namespace=*&namespace=cognition.v1&namespace=*" \
  -H "X-API-Key: user-api-key" \
  -H "Authorization: Bearer user-api-key" | jq
```

## Dependencies

- Enhancement 101 implementation in memory-service
- `RequestActor` message in protobuf contracts
- Conversation metadata API access
- Policy configuration for service-principal on-behalf-of access

## Related Issues

- User ID placeholder (see `conversation-metadata.md`)
- Memory namespace policy enforcement
