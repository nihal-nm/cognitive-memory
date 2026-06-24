package io.github.rigazilla.memory.cognition.health;

import io.github.rigazilla.memory.cognition.event.GrpcAdminEventClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Health check for gRPC connection to memory-service.
 * Reports readiness based on whether the event stream client is connected.
 */
@Readiness
@ApplicationScoped
public class GrpcConnectionHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(GrpcConnectionHealthCheck.class);

    @Inject
    GrpcAdminEventClient grpcClient;

    @Override
    public HealthCheckResponse call() {
        try {
            boolean isConnected = grpcClient.isConnected();
            
            HealthCheckResponseBuilder builder = HealthCheckResponse.named("grpc-memory-service");
            
            if (isConnected) {
                return builder
                    .up()
                    .withData("status", "connected")
                    .withData("host", grpcClient.getHost())
                    .withData("port", grpcClient.getPort())
                    .build();
            } else {
                return builder
                    .down()
                    .withData("status", "disconnected")
                    .withData("host", grpcClient.getHost())
                    .withData("port", grpcClient.getPort())
                    .build();
            }
        } catch (Exception e) {
            LOG.error("Error checking gRPC connection health", e);
            return HealthCheckResponse.named("grpc-memory-service")
                .down()
                .withData("status", "error")
                .withData("error", e.getMessage())
                .build();
        }
    }
}