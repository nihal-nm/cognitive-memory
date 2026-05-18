package io.github.rigazilla.memory.health;

import io.github.rigazilla.memory.service.MemoryServiceEventClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Health check for the memory-service event stream connection.
 */
@Liveness
@ApplicationScoped
public class EventStreamHealthCheck implements HealthCheck {

    @Inject
    MemoryServiceEventClient eventClient;

    @Override
    public HealthCheckResponse call() {
        boolean connected = eventClient.isConnected();

        return HealthCheckResponse.named("memory-service-event-stream")
                .status(connected)
                .withData("connected", connected)
                .build();
    }
}
