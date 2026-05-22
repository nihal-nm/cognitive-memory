# TODO: Testing

**Priority**: MEDIUM  
**Status**: Future work

## Problem

Phase 3 implementation has no automated tests. All verification was done manually.

## Current State

- ❌ No unit tests
- ❌ No integration tests
- ✅ Manual end-to-end testing only

## Test Coverage Needed

### Unit Tests

#### 1. DirtyWindow Tests
```java
@QuarkusTest
class DirtyWindowTest {
    
    @Test
    void testWindowAcceptsEvents() {
        DirtyWindow window = new DirtyWindow("conv-123", Instant.now());
        window.acceptEvent("entry-1");
        assertEquals(1, window.size());
    }
    
    @Test
    void testWindowPromotionOnDebounceDelay() {
        // Create window with old firstEventTime
        // Verify shouldPromote returns DEBOUNCE_DELAY
    }
    
    @Test
    void testWindowPromotionOnMaxBatchAge() {
        // Create window with old createdAt
        // Verify shouldPromote returns MAX_BATCH_AGE
    }
    
    @Test
    void testWindowPromotionOnMaxEntries() {
        // Add 24 entries
        // Verify shouldPromote returns MAX_ENTRIES
    }
}
```

#### 2. TranscriptLoader Tests
```java
@QuarkusTest
class TranscriptLoaderTest {
    
    @InjectMock
    EntriesServiceGrpc.EntriesServiceBlockingStub entriesStub;
    
    @Inject
    TranscriptLoader loader;
    
    @Test
    void testLoadTranscriptSuccess() {
        // Mock gRPC response
        when(entriesStub.listEntries(any())).thenReturn(mockResponse);
        
        EvidencePack pack = loader.loadTranscript("conv-123");
        
        assertNotNull(pack);
        assertEquals(3, pack.size());
    }
    
    @Test
    void testLoadTranscriptFiltersHistoryChannel() {
        // Verify only HISTORY channel entries are loaded
    }
    
    @Test
    void testLoadTranscriptHandlesEmptyConversation() {
        // Mock empty response
        // Verify empty EvidencePack returned
    }
}
```

#### 3. DurableMemoryExtractor Tests
```java
@QuarkusTest
class DurableMemoryExtractorTest {
    
    @InjectMock
    DurableMemoryExtractor extractor;
    
    @Test
    void testExtractMemoriesFromEvidence() {
        String evidence = "User: I work at Acme Corp as a senior engineer";
        
        DurableExtractionResponse response = extractor.extract(evidence);
        
        assertNotNull(response);
        assertFalse(response.facts().isEmpty());
        assertTrue(response.facts().get(0).content().contains("Acme Corp"));
    }
    
    @Test
    void testExtractMultipleMemoryTypes() {
        // Evidence with facts, preferences, procedures
        // Verify all types extracted
    }
}
```

#### 4. DurableMemoryVerifier Tests
```java
@QuarkusTest
class DurableMemoryVerifierTest {
    
    @InjectMock
    DurableMemoryVerifier verifier;
    
    @Test
    void testVerifyValidCitations() {
        MemoryCandidate candidate = new MemoryCandidate(
            MemoryType.FACT,
            "User works at Acme Corp",
            0.9,
            List.of("entry-1")
        );
        
        String evidence = "entry-1: User: I work at Acme Corp";
        
        DurableVerificationResponse response = verifier.verify(
            List.of(candidate), evidence
        );
        
        assertEquals(1, response.verified().size());
        assertTrue(response.rejected().isEmpty());
    }
    
    @Test
    void testRejectInvalidCitations() {
        // Candidate with citation not in evidence
        // Verify rejected with reason
    }
}
```

#### 5. MemoryWriter Tests
```java
@QuarkusTest
class MemoryWriterTest {
    
    @InjectMock
    MemoriesServiceGrpc.MemoriesServiceBlockingStub memoriesStub;
    
    @Inject
    MemoryWriter writer;
    
    @Test
    void testWriteMemoriesSuccess() {
        List<MemoryCandidate> memories = List.of(
            new MemoryCandidate(MemoryType.FACT, "content", 0.9, List.of("e1"))
        );
        
        writer.writeMemories("user-123", memories);
        
        verify(memoriesStub, times(1)).putMemory(any());
    }
    
    @Test
    void testWriteMemoriesCorrectNamespace() {
        // Verify namespace is ["user", userId, "cognition.v1", memoryType]
    }
}
```

#### 6. ConversationJobQueue Tests
```java
@QuarkusTest
class ConversationJobQueueTest {
    
    @Test
    void testEnqueueJob() {
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        ScopeJob job = new ScopeJob("conv-123", List.of("e1"), PromotionTrigger.DEBOUNCE_DELAY);
        
        queue.enqueue(job);
        
        assertEquals(1, queue.size());
    }
    
    @Test
    void testSingletonProcessing() {
        ConversationJobQueue queue = new ConversationJobQueue("conv-123");
        
        assertTrue(queue.tryStartProcessing());
        assertFalse(queue.tryStartProcessing()); // Already processing
        
        queue.finishProcessing();
        assertTrue(queue.tryStartProcessing()); // Can process again
    }
}
```

#### 7. JobProcessor Tests
```java
@QuarkusTest
class JobProcessorTest {
    
    @InjectMock
    TranscriptLoader transcriptLoader;
    
    @InjectMock
    DurableMemoryExtractor extractor;
    
    @InjectMock
    DurableMemoryVerifier verifier;
    
    @InjectMock
    MemoryWriter writer;
    
    @Inject
    JobProcessor processor;
    
    @Test
    void testProcessJobSuccess() {
        // Mock all pipeline stages
        when(transcriptLoader.loadTranscript(any())).thenReturn(mockEvidence);
        when(extractor.extract(any())).thenReturn(mockExtraction);
        when(verifier.verify(any(), any())).thenReturn(mockVerification);
        
        ScopeJob job = new ScopeJob("conv-123", List.of("e1"), PromotionTrigger.DEBOUNCE_DELAY);
        
        // Should not throw
        assertDoesNotThrow(() -> processor.processJob(job));
        
        verify(writer, times(1)).writeMemories(any(), any());
    }
    
    @Test
    void testProcessJobHandlesErrors() {
        // Mock failure in one stage
        when(transcriptLoader.loadTranscript(any())).thenThrow(new RuntimeException("Network error"));
        
        ScopeJob job = new ScopeJob("conv-123", List.of("e1"), PromotionTrigger.DEBOUNCE_DELAY);
        
        // Should log error but not throw
        assertDoesNotThrow(() -> processor.processJob(job));
        
        verify(writer, never()).writeMemories(any(), any());
    }
}
```

### Integration Tests

#### 1. End-to-End Pipeline Test
```java
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
class PipelineIntegrationTest {
    
    @Inject
    GrpcAdminEventClient eventClient;
    
    @Inject
    JobQueueRegistry queueRegistry;
    
    @Test
    void testEndToEndProcessing() throws Exception {
        // 1. Start event stream
        eventClient.start();
        
        // 2. Create test conversation in memory-service
        String conversationId = createTestConversation();
        
        // 3. Add entries
        addTestEntries(conversationId);
        
        // 4. Wait for debounce (1 minute)
        Thread.sleep(65000);
        
        // 5. Verify job was processed
        // Check logs or metrics
        
        // 6. Verify memories written
        List<Memory> memories = queryMemories("user-123");
        assertFalse(memories.isEmpty());
    }
}
```

#### 2. Ollama Integration Test
```java
@QuarkusTest
@TestProfile(OllamaTestProfile.class)
class OllamaIntegrationTest {
    
    @Inject
    DurableMemoryExtractor extractor;
    
    @Inject
    DurableMemoryVerifier verifier;
    
    @Test
    void testRealLLMExtraction() {
        String evidence = """
            User: I work at Acme Corp as a senior engineer.
            User: I prefer using Go for backend services.
            """;
        
        DurableExtractionResponse response = extractor.extract(evidence);
        
        assertNotNull(response);
        assertFalse(response.facts().isEmpty());
        // Verify LLM extracted meaningful memories
    }
    
    @Test
    void testRealLLMVerification() {
        // Test with real Ollama model
    }
}
```

### Test Profiles

```java
public class IntegrationTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "memory-service.grpc.host", "localhost",
            "memory-service.grpc.port", "8082",
            "cognition.scheduler.debounce-delay", "PT5S", // Faster for tests
            "quarkus.langchain4j.ollama.base-url", "http://localhost:11434"
        );
    }
}

public class OllamaTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.langchain4j.ollama.base-url", "http://localhost:11434",
            "quarkus.langchain4j.ollama.timeout", "120s"
        );
    }
    
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        // Use real Ollama, not mocks
        return Set.of();
    }
}
```

## Test Infrastructure

### Mock gRPC Services
```java
@ApplicationScoped
@Alternative
@Priority(1)
public class MockEntriesService extends EntriesServiceGrpc.EntriesServiceImplBase {
    
    private final Map<String, List<Entry>> mockData = new ConcurrentHashMap<>();
    
    @Override
    public void listEntries(ListEntriesRequest request, StreamObserver<ListEntriesResponse> responseObserver) {
        String conversationId = bytesToUuid(request.getConversationId());
        List<Entry> entries = mockData.getOrDefault(conversationId, List.of());
        
        ListEntriesResponse response = ListEntriesResponse.newBuilder()
            .addAllEntries(entries)
            .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    public void addMockEntries(String conversationId, List<Entry> entries) {
        mockData.put(conversationId, entries);
    }
}
```

### Test Utilities
```java
public class TestDataFactory {
    
    public static ScopeJob createTestJob(String conversationId) {
        return new ScopeJob(
            conversationId,
            List.of("entry-1", "entry-2"),
            PromotionTrigger.DEBOUNCE_DELAY
        );
    }
    
    public static EvidencePack createTestEvidence() {
        return new EvidencePack(List.of(
            "User: I work at Acme Corp",
            "User: I prefer Go for backend"
        ));
    }
    
    public static MemoryCandidate createTestCandidate() {
        return new MemoryCandidate(
            MemoryType.FACT,
            "User works at Acme Corp",
            0.9,
            List.of("entry-1")
        );
    }
}
```

## Running Tests

```bash
# Unit tests only
mvn test

# Integration tests (requires memory-service + Ollama)
mvn verify -Pintegration-tests

# Specific test class
mvn test -Dtest=DirtyWindowTest

# With coverage
mvn test jacoco:report
```

## CI/CD Integration

```yaml
# .github/workflows/test.yml
name: Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - name: Run unit tests
        run: mvn test
      - name: Upload coverage
        uses: codecov/codecov-action@v3

  integration-tests:
    runs-on: ubuntu-latest
    services:
      memory-service:
        image: memory-service:latest
        ports:
          - 8082:8082
      ollama:
        image: ollama/ollama:latest
        ports:
          - 11434:11434
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - name: Pull Ollama model
        run: docker exec ollama ollama pull llama3.2
      - name: Run integration tests
        run: mvn verify -Pintegration-tests
```

## Coverage Goals

- **Unit Tests**: 80% line coverage
- **Integration Tests**: Critical paths covered
- **E2E Tests**: Happy path + error scenarios

## References

- Quarkus Testing Guide: https://quarkus.io/guides/getting-started-testing
- Quarkus Test Profiles: https://quarkus.io/guides/getting-started-testing#testing-different-profiles
- gRPC Testing: https://grpc.io/docs/languages/java/basics/#testing
