package io.github.rigazilla.memory.cognition.process;

/**
 * Common contract for managed cognitive processes.
 */
public interface CognitiveProcess {

    String id();

    String displayName();

    String description();

    boolean supportsStart();

    boolean supportsEnable();

    boolean supportsDisable();

    ManagedProcessState state();

    ManagedProcessInspection inspect();

    default void start() {
        throw new UnsupportedOperationException("start is not implemented for process " + id());
    }

    default void enable() {
        throw new UnsupportedOperationException("enable is not implemented for process " + id());
    }

    default void disable() {
        throw new UnsupportedOperationException("disable is not implemented for process " + id());
    }
}
