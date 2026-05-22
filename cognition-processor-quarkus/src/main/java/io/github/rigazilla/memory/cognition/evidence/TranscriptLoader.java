package io.github.rigazilla.memory.cognition.evidence;

import com.google.protobuf.ByteString;
import io.github.chirino.memory.grpc.v1.Channel;
import io.github.chirino.memory.grpc.v1.EntriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.Entry;
import io.github.chirino.memory.grpc.v1.ListEntriesRequest;
import io.github.chirino.memory.grpc.v1.ListEntriesResponse;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads conversation transcript from Memory Service via gRPC.
 * Calls EntriesService.ListEntries to fetch history channel entries.
 */
@ApplicationScoped
public class TranscriptLoader {
    
    private static final Logger LOG = Logger.getLogger(TranscriptLoader.class);
    
    @ConfigProperty(name = "memory-service.grpc.host")
    String grpcHost;
    
    @ConfigProperty(name = "memory-service.grpc.port")
    int grpcPort;
    
    @ConfigProperty(name = "memory-service.api-key")
    String apiKey;
    
    private ManagedChannel channel;
    private EntriesServiceGrpc.EntriesServiceBlockingStub entriesStub;
    
    @PostConstruct
    void init() {
        LOG.infof("Initializing TranscriptLoader: %s:%d", grpcHost, grpcPort);
        
        // Create gRPC channel with authentication interceptor
        channel = ManagedChannelBuilder
            .forAddress(grpcHost, grpcPort)
            .usePlaintext()
            .intercept(new AuthInterceptor(apiKey))
            .build();
        
        // Create stub
        entriesStub = EntriesServiceGrpc.newBlockingStub(channel);
        
        LOG.info("TranscriptLoader initialized successfully");
    }
    
    /**
     * Interceptor that adds authentication headers to all gRPC calls.
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
                    // Add authentication headers
                    headers.put(Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER), apiKey);
                    headers.put(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + apiKey);
                    super.start(responseListener, headers);
                }
            };
        }
    }
    
    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down TranscriptLoader gRPC channel");
            channel.shutdown();
        }
    }
    
    /**
     * Load transcript for a conversation.
     * 
     * @param conversationId Conversation UUID
     * @return EvidencePack containing transcript entries
     */
    public EvidencePack loadTranscript(String conversationId) {
        try {
            LOG.debugf("Loading transcript for conversation: %s", conversationId);
            
            // Convert conversation ID string to UUID bytes
            ByteString conversationIdBytes = uuidToBytes(conversationId);
            
            // Build request for history channel entries
            ListEntriesRequest request = ListEntriesRequest.newBuilder()
                .setConversationId(conversationIdBytes)
                .setChannel(Channel.HISTORY)
                .build();
            
            // Call gRPC service
            ListEntriesResponse response = entriesStub.listEntries(request);
            List<Entry> entries = response.getEntriesList();
            
            LOG.infof("Loaded %d transcript entries for conversation %s", entries.size(), conversationId);
            
            // Convert to EvidencePack
            return new EvidencePack(entries);
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to load transcript for conversation %s", conversationId);
            throw new TranscriptLoadException("Failed to load transcript for conversation " + conversationId, e);
        }
    }
    
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
    
    /**
     * Exception thrown when transcript loading fails.
     */
    public static class TranscriptLoadException extends RuntimeException {
        public TranscriptLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
