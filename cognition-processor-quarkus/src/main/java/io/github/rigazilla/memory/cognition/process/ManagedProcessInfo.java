package io.github.rigazilla.memory.cognition.process;

/**
 * Summary view of a managed cognitive process.
 */
public record ManagedProcessInfo(
    String id,
    String displayName,
    String description,
    ManagedProcessState state
) {
}
