package io.github.rigazilla.memory.cognition.metadata;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesRequest;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.chirino.memory.grpc.v1.MemoryNamespace;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.github.rigazilla.memory.cognition.resource.LlmRetryHelper;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the metadata enrichment pass over existing memories.
 * Discovers all user memory namespaces, pages through each, and enriches
 * memories that lack entity/topic metadata by calling the LLM extractor.
 */
@ApplicationScoped
public class MetadataEnrichmentService {

    private static final Logger LOG = Logger.getLogger(MetadataEnrichmentService.class);
    private static final String COGNITION_VERSION = "cognition.v1";
    private static final String PROFILE_CONTEXT_TYPE = "profile_context";

    @ConfigProperty(name = "memory-service.grpc.host")
    String grpcHost;

    @ConfigProperty(name = "memory-service.grpc.port")
    int grpcPort;

    @ConfigProperty(name = "memory-service.api-key")
    String apiKey;

    @Inject
    MetadataExtractor extractor;

    @Inject
    LlmRetryHelper llmRetryHelper;

    @ConfigProperty(name = "cognition.llm.backfill.inter-call-delay-ms", defaultValue = "0")
    long interCallDelayMs;

    // Package-private for test injection (same pattern as TemporalMetadataEnrichmentService)
    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;

    // Progress state — exposed to MetadataEnrichmentProcess for inspect()
    // Package-private for direct counter inspection in unit tests
    final AtomicBoolean running = new AtomicBoolean(false);
    final AtomicReference<String> status = new AtomicReference<>("idle");
    final AtomicInteger processed = new AtomicInteger(0);
    final AtomicInteger enriched = new AtomicInteger(0);
    final AtomicInteger errors = new AtomicInteger(0);
    final AtomicReference<Instant> lastRunTime = new AtomicReference<>();

    @PostConstruct
    void init() {
        LOG.infof("Initializing MetadataEnrichmentService: %s:%d", grpcHost, grpcPort);
        channel = ManagedChannelBuilder.forAddress(grpcHost, grpcPort)
                .usePlaintext()
                .intercept(new AuthInterceptor(apiKey))
                .build();
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        LOG.info("MetadataEnrichmentService initialized successfully");
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down MetadataEnrichmentService gRPC channel");
            channel.shutdown();
        }
    }

    public String getStatus() {
        return status.get();
    }

    public int getProcessed() {
        return processed.get();
    }

    public int getEnriched() {
        return enriched.get();
    }

    public int getErrors() {
        return errors.get();
    }

    public Instant getLastRunTime() {
        return lastRunTime.get();
    }

    /**
     * Start enrichment asynchronously so start() does not block.
     * Silently skips if a run is already in progress.
     */
    public void startEnrichmentAsync() {
        if (!running.compareAndSet(false, true)) {
            LOG.info("Enrichment already running — skipping duplicate start");
            return;
        }
        status.set("running");

        CompletableFuture.runAsync(this::runEnrichment, Executors.newVirtualThreadPerTaskExecutor())
                .whenComplete((v, ex) -> {
                    lastRunTime.set(Instant.now());
                    running.set(false);
                    if (ex != null) {
                        LOG.errorf(ex, "Enrichment run failed");
                        status.set("error: " + ex.getMessage());
                    } else {
                        status.set("completed");
                    }
                });
    }

    // Package-private for direct invocation in unit tests
    void runEnrichment() {
        processed.set(0);
        enriched.set(0);
        errors.set(0);

        // Activate CDI request context once for the entire pass — required for
        // @RequestScoped LangChain4j AI services running on the CompletableFuture
        // virtual thread. Same pattern as JobProcessor.processJob().
        // Guard against null container (e.g. in plain unit tests without CDI runtime).
        var arcContainer = Arc.container();
        ManagedContext requestContext = arcContainer != null ? arcContainer.requestContext() : null;
        if (requestContext != null && !requestContext.isActive()) {
            requestContext.activate();
        }
        try {
            doRunEnrichment();
        } finally {
            if (requestContext != null && requestContext.isActive()) {
                requestContext.terminate();
            }
        }
    }

    private void doRunEnrichment() {
        LOG.info("Starting metadata enrichment pass");

        // Discover all namespaces rooted at "user"
        AdminListMemoryNamespacesRequest nsReq = AdminListMemoryNamespacesRequest.newBuilder()
                .addNamespacePrefix("user")
                .setMaxDepth(4)   // segments: user / userId / cognition.v1 / memoryType
                .build();

        List<MemoryNamespace> namespaces = memoriesStub
                .listNamespaces(nsReq)
                .getNamespacesList();

        LOG.infof("Discovered %d namespaces for enrichment", namespaces.size());

        for (MemoryNamespace ns : namespaces) {
            List<String> segments = ns.getSegmentsList();
            // Only process 4-segment cognition.v1 namespaces; skip profile_context snapshots
            if (segments.size() < 4) {
                continue;
            }
            if (!COGNITION_VERSION.equals(segments.get(2))) {
                continue;
            }
            String memoryType = segments.get(3);
            if (PROFILE_CONTEXT_TYPE.equals(memoryType)) {
                continue;
            }
            enrichNamespace(segments, memoryType);
        }

        LOG.infof("Enrichment pass complete: processed=%d, enriched=%d, errors=%d",
                processed.get(), enriched.get(), errors.get());
    }

    private void enrichNamespace(List<String> namespace, String memoryType) {
        String cursor = null;
        do {
            AdminListMemoriesRequest.Builder reqBuilder = AdminListMemoriesRequest.newBuilder()
                    .addAllNamespacePrefix(namespace)
                    .setLimit(50);
            if (cursor != null) {
                reqBuilder.setAfterCursor(cursor);
            }

            AdminListMemoriesResponse resp = memoriesStub.listMemories(reqBuilder.build());

            for (AdminMemoryItem item : resp.getItemsList()) {
                enrichMemory(item, memoryType);
                sleepInterCallDelay();
            }

            cursor = resp.hasAfterCursor() ? resp.getAfterCursor() : null;
        } while (cursor != null);
    }

    private void enrichMemory(AdminMemoryItem item, String memoryType) {
        try {
            Struct value = item.getValue();

            // Idempotent: skip memories already enriched
            if (value.containsFields("entities")) {
                return;
            }

            String content = value.getFieldsOrDefault(
                    "content",
                    Value.newBuilder().setStringValue("").build()
            ).getStringValue();

            if (content.isBlank()) {
                return;
            }

            // Call LLM to extract entities and topics — wrapped with retry/backoff
            MetadataExtractionResponse extraction = llmRetryHelper.withRetry(
                    "metadata-extraction:" + item.getKey(),
                    () -> extractor.extract(memoryType, content));

            // Write back: copy all existing fields and add entities + topics
            Struct updatedValue = value.toBuilder()
                    .putFields("entities", buildEntitiesValue(extraction.entities()))
                    .putFields("topics", buildTopicsValue(extraction.topics()))
                    .build();

            AdminPutMemoryRequest putReq = AdminPutMemoryRequest.newBuilder()
                    .addAllNamespace(item.getNamespaceList())
                    .setKey(item.getKey())
                    .setValue(updatedValue)
                    .setExpectedRevision(item.getRevision())
                    .build();

            memoriesStub.putMemory(putReq);
            enriched.incrementAndGet();

            LOG.debugf("Enriched memory: key=%s, entities=%d, topics=%d",
                    item.getKey(), extraction.entities().size(), extraction.topics().size());

        } catch (Exception e) {
            errors.incrementAndGet();
            LOG.warnf("Failed to enrich memory key=%s: %s", item.getKey(), e.getMessage());
        } finally {
            processed.incrementAndGet();
        }
    }

    private Value buildEntitiesValue(List<ExtractedEntity> entities) {
        ListValue.Builder list = ListValue.newBuilder();
        for (ExtractedEntity e : entities) {
            Struct entityStruct = Struct.newBuilder()
                    .putFields("name", Value.newBuilder().setStringValue(e.name()).build())
                    .putFields("type", Value.newBuilder().setStringValue(e.type()).build())
                    .build();
            list.addValues(Value.newBuilder().setStructValue(entityStruct).build());
        }
        return Value.newBuilder().setListValue(list.build()).build();
    }

    private void sleepInterCallDelay() {
        if (interCallDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(interCallDelayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Value buildTopicsValue(List<String> topics) {
        ListValue.Builder list = ListValue.newBuilder();
        for (String t : topics) {
            list.addValues(Value.newBuilder().setStringValue(t).build());
        }
        return Value.newBuilder().setListValue(list.build()).build();
    }

    /**
     * Interceptor that adds authentication headers to all gRPC calls.
     * Same pattern as MemoryWriter and ProfileContextService.
     */
    private static class AuthInterceptor implements ClientInterceptor {
        private final String apiKey;

        AuthInterceptor(String key) {
            this.apiKey = key;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions opts, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, opts)) {
                @Override
                public void start(Listener<RespT> listener, Metadata headers) {
                    headers.put(
                            Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER),
                            apiKey
                    );
                    super.start(listener, headers);
                }
            };
        }
    }
}
