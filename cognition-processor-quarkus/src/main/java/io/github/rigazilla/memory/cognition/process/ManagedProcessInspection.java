package io.github.rigazilla.memory.cognition.process;

import java.util.Map;

/**
 * Detailed inspection view of a managed cognitive process.
 */
public record ManagedProcessInspection(
    String id,
    String displayName,
    String description,
    ManagedProcessState state,
    Map<String, Object> details
) {
    public ManagedProcessInfo toInfo() {
        return new ManagedProcessInfo(
            id,
            displayName,
            description,
            state
        );
    }
}
