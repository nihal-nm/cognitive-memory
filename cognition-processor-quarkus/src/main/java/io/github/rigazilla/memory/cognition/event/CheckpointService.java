package io.github.rigazilla.memory.cognition.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing event stream checkpoints with embedded dirty windows.
 * Phase 1: File-based storage for testing.
 * 
 * TODO: Replace with gRPC AdminCheckpointService calls when available.
 */
@ApplicationScoped
public class CheckpointService {

    private static final Logger LOG = Logger.getLogger(CheckpointService.class);
    private static final String CHECKPOINT_DIR = "/tmp/cognition-checkpoints";
    
    private final ObjectMapper objectMapper;
    
    public CheckpointService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        
        // Ensure checkpoint directory exists
        try {
            Files.createDirectories(Paths.get(CHECKPOINT_DIR));
        } catch (IOException e) {
            LOG.errorf(e, "Failed to create checkpoint directory: %s", CHECKPOINT_DIR);
        }
    }

    /**
     * Load checkpoint state for a worker.
     * 
     * @param workerId Worker identifier
     * @return CheckpointState or null if not found
     */
    public CheckpointState loadCheckpoint(String workerId) {
        Path checkpointPath = getCheckpointPath(workerId);
        
        if (!Files.exists(checkpointPath)) {
            LOG.infof("No checkpoint found for worker: %s", workerId);
            return null;
        }
        
        try {
            CheckpointState state = objectMapper.readValue(checkpointPath.toFile(), CheckpointState.class);
            LOG.infof("Loaded checkpoint for worker %s: cursor=%s, windows=%d", 
                     workerId, state.lastEventCursor(), state.dirtyWindows().size());
            return state;
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load checkpoint for worker: %s", workerId);
            return null;
        }
    }

    /**
     * Save checkpoint state for a worker.
     * 
     * @param workerId Worker identifier
     * @param state Checkpoint state to save
     */
    public void saveCheckpoint(String workerId, CheckpointState state) {
        Path checkpointPath = getCheckpointPath(workerId);
        
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                       .writeValue(checkpointPath.toFile(), state);
            LOG.infof("Saved checkpoint for worker %s: cursor=%s, windows=%d", 
                     workerId, state.lastEventCursor(), state.dirtyWindows().size());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to save checkpoint for worker: %s", workerId);
        }
    }
    
    /**
     * Save checkpoint with cursor and dirty windows.
     * 
     * @param workerId Worker identifier
     * @param cursor Event cursor
     * @param runtimeId Runtime identifier
     * @param runtimeVersion Runtime version
     * @param dirtyWindows List of serialized windows
     */
    public void saveCheckpoint(String workerId, String cursor, String runtimeId, String runtimeVersion,
                               List<SerializedWindow> dirtyWindows) {
        CheckpointState state = new CheckpointState(
            cursor,
            Instant.now(),
            runtimeId,
            runtimeVersion,
            Instant.now(), // highestEventTimestamp
            dirtyWindows
        );
        
        saveCheckpoint(workerId, state);
    }
    
    private Path getCheckpointPath(String workerId) {
        return Paths.get(CHECKPOINT_DIR, workerId + ".json");
    }
}

/**
 * Checkpoint state with embedded dirty windows.
 * 
 * @param lastEventCursor Last processed event cursor
 * @param updatedAt When this checkpoint was saved
 * @param runtimeId Runtime identifier
 * @param runtimeVersion Runtime version
 * @param highestEventTimestamp Highest event timestamp observed
 * @param dirtyWindows List of open dirty windows
 */
record CheckpointState(
    String lastEventCursor,
    Instant updatedAt,
    String runtimeId,
    String runtimeVersion,
    Instant highestEventTimestamp,
    List<SerializedWindow> dirtyWindows
) {
    public CheckpointState {
        // Ensure dirtyWindows is never null
        if (dirtyWindows == null) {
            dirtyWindows = new ArrayList<>();
        }
    }
}
