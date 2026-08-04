package io.github.rigazilla.memory.cognition.event;

import io.github.chirino.memory.grpc.v1.EventNotification;
import io.github.chirino.memory.grpc.v1.EventScope;
import io.github.chirino.memory.grpc.v1.EventStreamServiceGrpc;
import io.github.chirino.memory.grpc.v1.SubscribeEventsRequest;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrpcAdminEventClient.
 * 
 * Tests cover:
 * - Connection lifecycle (startup, shutdown, reconnect)
 * - Event handling (conversation, entry, invalidate events)
 * - Checkpoint management (save, restore, reset)
 * - Error handling and recovery
 * - JSON field extraction
 * - Metrics tracking
 */
class GrpcAdminEventClientTest {

    private GrpcAdminEventClient client;
    private CheckpointService checkpointService;
    private DirtyWindowRegistry windowRegistry;
    private ManagedChannel mockChannel;
    private EventStreamServiceGrpc.EventStreamServiceStub mockStub;

    @BeforeEach
    void setUp() {
        client = new GrpcAdminEventClient();
        checkpointService = mock(CheckpointService.class);
        windowRegistry = mock(DirtyWindowRegistry.class);
        mockChannel = mock(ManagedChannel.class);
        mockStub = mock(EventStreamServiceGrpc.EventStreamServiceStub.class);

        // Inject mocks
        client.checkpointService = checkpointService;
        client.windowRegistry = windowRegistry;
        
        // Set config properties
        client.grpcHost = "localhost";
        client.grpcPort = 8082;
        client.apiKey = "test-api-key";
        client.clientId = "test-client";
        client.workerId = "test-worker";
        client.runtimeId = "test-runtime";
        client.runtimeVersion = "1";
        client.resetCheckpointOnStartup = false;
    }



    @Test
    void testOnShutdown_SavesCheckpoint() {
        // Given: Client has processed events
        client.lastEventCursor = "cursor-final";
        when(windowRegistry.serializeWindows()).thenReturn(List.of());

        // When: Shutdown event is observed
        ShutdownEvent shutdownEvent = mock(ShutdownEvent.class);
        client.onShutdown(shutdownEvent);

        // Then: Should save checkpoint
        verify(checkpointService).saveCheckpoint(
            eq("test-worker"),
            eq("cursor-final"),
            eq("test-runtime"),
            eq("1"),
            anyList()
        );
    }

    @Test
    void testHandleEvent_ConversationEvent_AcceptsIntoRegistry() {
        // Given: Event with conversation ID
        EventNotification event = EventNotification.newBuilder()
            .setEvent("conversation.created")
            .setKind("conversation")
            .setCursor("cursor-1")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"conversation_id\":\"conv-123\",\"entry\":\"entry-456\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should accept into window registry
        verify(windowRegistry).acceptEvent(
            eq("conv-123"),
            eq("cursor-1"),
            eq("entry-456"),
            any(Instant.class)
        );
    }

    @Test
    void testHandleEvent_EntryEvent_ExtractsEntryId() {
        // Given: Entry event with entry field
        EventNotification event = EventNotification.newBuilder()
            .setEvent("entry.created")
            .setKind("entry")
            .setCursor("cursor-2")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"conversation\":\"conv-789\",\"entry\":\"entry-999\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should extract entry ID correctly
        verify(windowRegistry).acceptEvent(
            eq("conv-789"),
            eq("cursor-2"),
            eq("entry-999"),
            any(Instant.class)
        );
    }

    @Test
    void testHandleEvent_UpdatesCursor() {
        // Given: Event with cursor
        EventNotification event = EventNotification.newBuilder()
            .setEvent("test.event")
            .setKind("test")
            .setCursor("cursor-new")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"conversation_id\":\"conv-1\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should update last cursor
        assertEquals("cursor-new", client.lastEventCursor);
    }

    @Test
    void testHandleEvent_PeriodicCheckpoint_Every10Events() {
        // Given: Multiple events
        when(windowRegistry.serializeWindows()).thenReturn(List.of());

        // When: Process 10 events
        for (int i = 1; i <= 10; i++) {
            EventNotification event = EventNotification.newBuilder()
                .setEvent("test.event")
                .setCursor("cursor-" + i)
                .setData(com.google.protobuf.ByteString.copyFromUtf8(
                    "{\"conversation_id\":\"conv-1\"}"
                ))
                .build();
            client.handleEvent(event);
        }

        // Then: Should save checkpoint once (at event 10)
        verify(checkpointService, times(1)).saveCheckpoint(
            anyString(), anyString(), anyString(), anyString(), anyList()
        );
    }

    @Test
    void testHandleEvent_InvalidateEvent_ResetsCheckpoint() {
        // Given: Invalidate event with retention window reason
        EventNotification event = EventNotification.newBuilder()
            .setEvent("invalidate")
            .setKind("stream")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"reason\":\"cursor beyond retention window\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should reset checkpoint and clear windows
        verify(checkpointService).resetCheckpoint("test-worker", "test-runtime", "1");
        verify(windowRegistry).clear();
    }

    @Test
    void testHandleEvent_InvalidateEvent_OtherReason_LogsWarning() {
        // Given: Invalidate event with different reason
        EventNotification event = EventNotification.newBuilder()
            .setEvent("invalidate")
            .setKind("stream")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"reason\":\"some other reason\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should not reset checkpoint (only log warning)
        verify(checkpointService, never()).resetCheckpoint(anyString(), anyString(), anyString());
        verify(windowRegistry, never()).clear();
    }

    @Test
    void testHandleEvent_NoConversationId_SkipsRegistry() {
        // Given: Event without conversation ID
        EventNotification event = EventNotification.newBuilder()
            .setEvent("system.event")
            .setKind("system")
            .setCursor("cursor-sys")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"some_field\":\"value\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should not accept into registry
        verify(windowRegistry, never()).acceptEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testHandleEvent_EmptyData_HandlesGracefully() {
        // Given: Event with empty data
        EventNotification event = EventNotification.newBuilder()
            .setEvent("empty.event")
            .setKind("test")
            .setCursor("cursor-empty")
            .build();

        // When: Event is handled
        assertDoesNotThrow(() -> client.handleEvent(event));

        // Then: Should not crash
        verify(windowRegistry, never()).acceptEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testExtractJsonField_ValidField_ReturnsValue() {
        // Given: JSON with field
        String json = "{\"conversation_id\":\"conv-123\",\"other\":\"value\"}";

        // When: Extract field
        String result = client.extractJsonField(json, "conversation_id");

        // Then: Should return value
        assertEquals("conv-123", result);
    }

    @Test
    void testExtractJsonField_MissingField_ReturnsNull() {
        // Given: JSON without field
        String json = "{\"other\":\"value\"}";

        // When: Extract field
        String result = client.extractJsonField(json, "conversation_id");

        // Then: Should return null
        assertNull(result);
    }

    @Test
    void testExtractJsonField_MultipleFields_ReturnsFirst() {
        // Given: JSON with multiple fields
        String json = "{\"field1\":\"value1\",\"field2\":\"value2\"}";

        // When: Extract first field
        String result = client.extractJsonField(json, "field1");

        // Then: Should return first value
        assertEquals("value1", result);
    }

    @Test
    void testSaveCheckpoint_NoCursor_SkipsSave() {
        // Given: No cursor set
        client.lastEventCursor = null;

        // When: Save checkpoint
        client.saveCheckpoint();

        // Then: Should not save
        verify(checkpointService, never()).saveCheckpoint(
            anyString(), anyString(), anyString(), anyString(), anyList()
        );
    }

    @Test
    void testSaveCheckpoint_WithCursor_SavesState() {
        // Given: Cursor and windows
        client.lastEventCursor = "cursor-123";
        List<SerializedWindow> windows = List.of(
            new SerializedWindow("conv1", "cursor1", "cursor1", List.of(), null, Instant.now(), Instant.now(), Instant.now(), 0)
        );
        when(windowRegistry.serializeWindows()).thenReturn(windows);

        // When: Save checkpoint
        client.saveCheckpoint();

        // Then: Should save with cursor and windows
        verify(checkpointService).saveCheckpoint(
            eq("test-worker"),
            eq("cursor-123"),
            eq("test-runtime"),
            eq("1"),
            eq(windows)
        );
    }

    @Test
    void testSaveCheckpoint_Exception_LogsError() {
        // Given: Checkpoint service throws exception
        client.lastEventCursor = "cursor-123";
        when(windowRegistry.serializeWindows()).thenReturn(List.of());
        doThrow(new RuntimeException("Save failed"))
            .when(checkpointService).saveCheckpoint(anyString(), anyString(), anyString(), anyString(), anyList());

        // When: Save checkpoint
        assertDoesNotThrow(() -> client.saveCheckpoint());

        // Then: Should log error but not crash
        verify(checkpointService).saveCheckpoint(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void testGetEventCount_ReturnsAcceptedCount() {
        // Given: Events processed
        EventNotification event = EventNotification.newBuilder()
            .setEvent("test")
            .setCursor("c1")
            .setData(com.google.protobuf.ByteString.copyFromUtf8("{\"conversation_id\":\"c1\"}"))
            .build();
        
        client.handleEvent(event);
        client.handleEvent(event);
        client.handleEvent(event);

        // When: Get count
        long count = client.getEventCount();

        // Then: Should return 3
        assertEquals(3, count);
    }
}
