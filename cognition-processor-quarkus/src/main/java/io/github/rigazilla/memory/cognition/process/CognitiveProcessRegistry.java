package io.github.rigazilla.memory.cognition.process;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Registry of all managed cognitive processes.
 */
@ApplicationScoped
public class CognitiveProcessRegistry {

    private final List<CognitiveProcess> processes;

    @Inject
    public CognitiveProcessRegistry(Instance<CognitiveProcess> processInstances) {
        this.processes = processInstances.stream()
            .sorted(Comparator.comparing(CognitiveProcess::id))
            .toList();
    }

    public List<CognitiveProcess> list() {
        return processes;
    }

    public CognitiveProcess get(String processId) {
        return processes.stream()
            .filter(process -> process.id().equals(processId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("Unknown cognitive process: " + processId));
    }
}
