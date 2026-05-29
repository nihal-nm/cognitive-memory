# DONE: Conversation Metadata Integration

**Originally**: HIGH Priority - Blocking proper memory namespace  
**Status**: ✅ **COMPLETED**  
**Completed**: Phase 4-6 (see also: `005-conversation-owner-metadata.md`, `013-conversation-metadata-grpc-migration.md`)

## Summary

Successfully migrated JobProcessor to load real user IDs from conversation metadata via AdminConversationsService gRPC. Memories are now written to correct user namespaces (e.g., `["user", "alice", "cognition.v1", "fact"]`), supporting proper multi-user scenarios.

---

## Original Problem Statement

`JobProcessor` uses hardcoded `"user-placeholder"` as userId when writing memories:

```java
// In JobProcessor.processJob()
String userId = "user-placeholder"; // TODO: Extract from conversation metadata
memoryWriter.writeMemories(userId, verification.verified());
```

## Impact

- All memories written to namespace: `["user", "user-placeholder", "cognition.v1", *]`
- Memories not properly scoped to actual conversation owners
- Cannot query memories by real user ID
- Breaks multi-user scenarios

## Solution

### Step 1: Add ConversationsService Client

Update `JobProcessor` to load conversation metadata:

```java
@Inject
ConversationsServiceGrpc.ConversationsServiceBlockingStub conversationsStub;

private String getConversationOwner(String conversationId) {
    try {
        ByteString conversationIdBytes = uuidToBytes(conversationId);
        
        GetConversationRequest request = GetConversationRequest.newBuilder()
            .setId(conversationIdBytes)
            .build();
        
        Conversation conversation = conversationsStub.getConversation(request);
        
        // Extract owner user ID
        return conversation.getOwnerUserId();
        
    } catch (Exception e) {
        LOG.errorf(e, "Failed to load conversation metadata for %s", conversationId);
        throw new JobProcessingException("Failed to load conversation metadata", e);
    }
}
```

### Step 2: Update processJob()

Replace placeholder with real user ID:

```java
private void processJob(ScopeJob job) {
    LOG.infof("▶ Processing job: %s", job);
    long startTime = System.currentTimeMillis();
    
    try {
        // Stage 0: Load Conversation Metadata
        LOG.infof("  [0/4] Loading conversation metadata: %s", job.conversationId());
        String userId = getConversationOwner(job.conversationId());
        LOG.infof("  ✓ Conversation owner: %s", userId);
        
        // Stage 1: Load Evidence
        LOG.infof("  [1/4] Loading transcript for conversation: %s", job.conversationId());
        EvidencePack evidence = transcriptLoader.loadTranscript(job.conversationId());
        LOG.infof("  ✓ Loaded %d transcript entries", evidence.size());
        
        // ... rest of pipeline ...
        
        // Stage 4: Write Memories (now with real userId)
        if (!verification.verified().isEmpty()) {
            LOG.infof("  [4/4] Writing %d verified memories to memory-service", verification.verified().size());
            memoryWriter.writeMemories(userId, verification.verified());
            LOG.infof("  ✓ Successfully wrote %d memories for user %s", verification.verified().size(), userId);
        }
        
        // ... rest of implementation ...
    }
}
```

### Step 3: Add UUID Conversion Helper

```java
/**
 * Convert UUID string to protobuf ByteString (16-byte big-endian).
 */
private ByteString uuidToBytes(String uuidString) {
    try {
        UUID uuid = UUID.fromString(uuidString);
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(buffer.array());
    } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid UUID format: " + uuidString, e);
    }
}
```

### Step 4: Inject gRPC Client

Add to `JobProcessor` initialization:

```java
@ConfigProperty(name = "memory-service.grpc.host")
String grpcHost;

@ConfigProperty(name = "memory-service.grpc.port")
int grpcPort;

@ConfigProperty(name = "memory-service.api-key")
String apiKey;

private ManagedChannel channel;
private ConversationsServiceGrpc.ConversationsServiceBlockingStub conversationsStub;

@PostConstruct
void init() {
    LOG.info("Initializing JobProcessor gRPC clients");
    
    // Create gRPC channel with authentication
    channel = ManagedChannelBuilder
        .forAddress(grpcHost, grpcPort)
        .usePlaintext()
        .intercept(new AuthInterceptor(apiKey))
        .build();
    
    conversationsStub = ConversationsServiceGrpc.newBlockingStub(channel);
    
    LOG.info("JobProcessor gRPC clients initialized");
}

@PreDestroy
void cleanup() {
    if (channel != null && !channel.isShutdown()) {
        LOG.info("Shutting down JobProcessor gRPC channel");
        channel.shutdown();
    }
}

/**
 * Authentication interceptor for gRPC calls.
 */
private static class AuthInterceptor implements ClientInterceptor {
    private final String apiKey;
    
    AuthInterceptor(String apiKey) {
        this.apiKey = apiKey;
    }
    
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            io.grpc.Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER), apiKey);
                headers.put(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + apiKey);
                super.start(responseListener, headers);
            }
        };
    }
}
```

## Testing

After implementation:

```bash
# Create conversation as user "alice"
CONV_ID=$(curl -s -X POST http://localhost:8082/v1/conversations \
  -H "X-API-Key: alice-api-key" \
  -H "Authorization: Bearer alice-api-key" \
  -H "Content-Type: application/json" \
  -d '{"title": "Alice Test"}' | jq -r '.id')

# Add entries
curl -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
  -H "X-API-Key: alice-api-key" \
  -H "Authorization: Bearer alice-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "HISTORY",
    "content": [{"role": "user", "text": "I work at Acme Corp"}]
  }'

# Wait for processing, check logs
tail -f /tmp/quarkus-startup-final.log | grep "Conversation owner"

# Verify memories written to correct namespace
curl -s "http://localhost:8082/v1/memories?namespace=user&namespace=alice&namespace=cognition.v1&namespace=*" \
  -H "X-API-Key: alice-api-key" \
  -H "Authorization: Bearer alice-api-key" | jq

# Should see memories under ["user", "alice", "cognition.v1", "fact"]
# NOT under ["user", "user-placeholder", "cognition.v1", "fact"]
```

## Dependencies

- ConversationsService gRPC client
- Conversation metadata API access
- Same authentication as TranscriptLoader and MemoryWriter

## Related Issues

- Authentication/authorization (see `authentication-authorization.md`)
- Both issues may need conversation metadata access
