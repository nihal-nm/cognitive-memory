package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;

import java.util.Map;

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

    /**
     * Start with optional parameters. The default ignores params and delegates to
     * {@link #start()}, preserving backward compatibility for processes that do not
     * support parameterised starts.
     *
     * @param params arbitrary key/value pairs; processes that recognise specific keys
     *               (e.g. {@code "namespacePrefix"}) will act on them; all others ignore them.
     */
    default void start(Map<String, Object> params) {
        start();
    }

    default void enable() {
        throw new UnsupportedOperationException("enable is not implemented for process " + id());
    }

    default void disable() {
        throw new UnsupportedOperationException("disable is not implemented for process " + id());
    }

    /**
     * Get resource requirements for this process.
     * Returns null to use global defaults only.
     * 
     * @return The resource requirements, or null for global defaults
     */
    default ResourceRequirements getResourceRequirements() {
        return null;
    }
}
