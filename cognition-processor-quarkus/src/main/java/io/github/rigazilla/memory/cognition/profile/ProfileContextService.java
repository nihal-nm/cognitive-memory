package io.github.rigazilla.memory.cognition.profile;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.MemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.chirino.memory.grpc.v1.MemoryWriteResult;
import io.github.chirino.memory.grpc.v1.RequestActor;
import io.github.chirino.memory.grpc.v1.SearchMemoriesRequest;
import io.github.chirino.memory.grpc.v1.SearchMemoriesResponse;
import io.github.rigazilla.memory.cognition.writer.MemoryWriter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable infrastructure service for profile context consolidation.
 * Handles memory querying, consolidation orchestration, and snapshot writing.
 * The actual consolidation logic is delegated to a pluggable strategy.
 */
@ApplicationScoped
public class ProfileContextService {
    
    private static final Logger LOG = Logger.getLogger(ProfileContextService.class);
    private static final String COGNITION_VERSION = "cognition.v1";
    private static final String PROFILE_CONTEXT_TYPE = "profile_context";
    private static final String LATEST_KEY = "latest";
    
    @ConfigProperty(name = "memory-service.grpc.host")
    String grpcHost;
    
    @ConfigProperty(name = "memory-service.grpc.port")
    int grpcPort;
    
    @ConfigProperty(name = "memory-service.api-key")
    String apiKey;
    
    @Inject
    ProfileConsolidationStrategy consolidationStrategy;
    
    @Inject
    MemoryWriter memoryWriter;
    
    private ManagedChannel channel;
    private MemoriesServiceGrpc.MemoriesServiceBlockingStub memoriesStub;
    
    @PostConstruct
    void init() {
        LOG.infof("Initializing ProfileContextService: %s:%d", grpcHost, grpcPort);
        
        // Create gRPC channel with authentication
        channel = ManagedChannelBuilder
            .forAddress(grpcHost, grpcPort)
            .usePlaintext()
            .intercept(new AuthInterceptor(apiKey))
            .build();
        
        memoriesStub = MemoriesServiceGrpc.newBlockingStub(channel);
        
        LOG.info("ProfileContextService initialized successfully");
    }
    
    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down ProfileContextService gRPC channel");
            channel.shutdown();
        }
    }
    
    /**
     * Consolidate profile for a user.
     * This is the main orchestration method.
     * 
     * @param userId User ID to consolidate profile for
     * @return The generated profile snapshot
     */
    public ProfileSnapshot consolidateProfile(String userId) {
        LOG.infof("Starting profile consolidation for user: %s", userId);
        
        try {
            // Step 1: Query existing memories
            List<MemoryItem> memories = queryUserMemories(userId);
            LOG.infof("Loaded %d memories for user %s", memories.size(), userId);
            
            // Step 2: Consolidate using strategy (experimental component)
            ProfileSnapshot snapshot = consolidationStrategy.consolidate(memories, userId);
            LOG.infof("Profile consolidated for user %s", userId);
            
            // Step 3: Write snapshot to memory service
            writeSnapshot(userId, snapshot);
            LOG.infof("Profile snapshot written for user %s", userId);
            
            return snapshot;
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to consolidate profile for user %s", userId);
            throw new ProfileConsolidationException("Profile consolidation failed for user " + userId, e);
        }
    }
    
    /**
     * Query all memories from user's cognition namespace.
     * Stable infrastructure method.
     */
    private List<MemoryItem> queryUserMemories(String userId) {
        try {
            SearchMemoriesRequest request = SearchMemoriesRequest.newBuilder()
                .addNamespacePrefix("user")
                .addNamespacePrefix(userId)
                .addNamespacePrefix(COGNITION_VERSION)
                .setActor(RequestActor.newBuilder()
                    .setOnBehalfOfUserId(userId)
                    .build())
                .setLimit(100)  // Reasonable limit for Phase 0
                .build();
            
            SearchMemoriesResponse response = memoriesStub.searchMemories(request);
            return response.getItemsList();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query memories for user %s", userId);
            throw new RuntimeException("Failed to query user memories", e);
        }
    }
    
    /**
     * Write profile snapshot to memory service.
     * Stable infrastructure method.
     */
    private void writeSnapshot(String userId, ProfileSnapshot snapshot) {
        try {
            // Build namespace: ["user", userId, "cognition.v1", "profile_context"]
            List<String> namespace = List.of("user", userId, COGNITION_VERSION, PROFILE_CONTEXT_TYPE);
            
            // Build value struct
            Struct.Builder valueBuilder = Struct.newBuilder();
            valueBuilder.putFields("kind", Value.newBuilder().setStringValue("profile_context_snapshot").build());
            valueBuilder.putFields("version", Value.newBuilder().setStringValue("profile_context.v1").build());
            valueBuilder.putFields("user_id", Value.newBuilder().setStringValue(userId).build());
            valueBuilder.putFields("generated_at", Value.newBuilder().setStringValue(snapshot.generatedAt().toString()).build());
            valueBuilder.putFields("content", Value.newBuilder().setStringValue(snapshot.content()).build());
            
            // Build sections metadata
            Struct.Builder sectionsBuilder = Struct.newBuilder();
            for (Map.Entry<String, ProfileSnapshot.ProfileSection> entry : snapshot.sections().entrySet()) {
                ProfileSnapshot.ProfileSection section = entry.getValue();
                Struct.Builder sectionBuilder = Struct.newBuilder();
                sectionBuilder.putFields("confidence", Value.newBuilder().setNumberValue(section.confidence()).build());
                
                // Build source memory keys array
                com.google.protobuf.ListValue.Builder keysBuilder = com.google.protobuf.ListValue.newBuilder();
                for (String key : section.sourceMemoryKeys()) {
                    keysBuilder.addValues(Value.newBuilder().setStringValue(key).build());
                }
                sectionBuilder.putFields("source_memory_keys", Value.newBuilder().setListValue(keysBuilder.build()).build());
                
                sectionsBuilder.putFields(entry.getKey(), Value.newBuilder().setStructValue(sectionBuilder.build()).build());
            }
            valueBuilder.putFields("sections", Value.newBuilder().setStructValue(sectionsBuilder.build()).build());
            
            // Write using MemoryWriter's gRPC client
            io.github.chirino.memory.grpc.v1.PutMemoryRequest request = 
                io.github.chirino.memory.grpc.v1.PutMemoryRequest.newBuilder()
                    .addAllNamespace(namespace)
                    .setKey(LATEST_KEY)
                    .setValue(valueBuilder.build())
                    .setActor(RequestActor.newBuilder()
                        .setOnBehalfOfUserId(userId)
                        .build())
                    .build();
            
            MemoryWriteResult result = memoriesStub.putMemory(request);
            LOG.infof("Profile snapshot written: namespace=%s, key=%s", namespace, LATEST_KEY);
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to write profile snapshot for user %s", userId);
            throw new RuntimeException("Failed to write profile snapshot", e);
        }
    }
    
    /**
     * Interceptor that adds authentication headers to all gRPC calls.
     */
    private static class AuthInterceptor implements io.grpc.ClientInterceptor {
        private final String apiKey;
        
        AuthInterceptor(String apiKey) {
            this.apiKey = apiKey;
        }
        
        @Override
        public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                io.grpc.MethodDescriptor<ReqT, RespT> method,
                io.grpc.CallOptions callOptions,
                io.grpc.Channel next) {
            return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, io.grpc.Metadata headers) {
                    // Add authentication headers
                    headers.put(io.grpc.Metadata.Key.of("X-API-Key", io.grpc.Metadata.ASCII_STRING_MARSHALLER), apiKey);
                    headers.put(io.grpc.Metadata.Key.of("Authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "Bearer " + apiKey);
                    super.start(responseListener, headers);
                }
            };
        }
    }
    
    /**
     * Exception thrown when profile consolidation fails.
     */
    public static class ProfileConsolidationException extends RuntimeException {
        public ProfileConsolidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
