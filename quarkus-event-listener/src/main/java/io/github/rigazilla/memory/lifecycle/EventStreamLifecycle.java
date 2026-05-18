package io.github.rigazilla.memory.lifecycle;

import io.github.rigazilla.memory.listener.ListenerManager;
import io.github.rigazilla.memory.service.ConversationEventHandler;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Manages the lifecycle of the application.
 * Listeners are now started via REST API instead of auto-starting.
 */
@ApplicationScoped
public class EventStreamLifecycle {

    private static final Logger LOG = Logger.getLogger(EventStreamLifecycle.class);

    @Inject
    ListenerManager listenerManager;

    @Inject
    ConversationEventHandler eventHandler;

    void onStart(@Observes StartupEvent event) {
        LOG.info("🚀 Memory-service event listener application started");
        LOG.info("📡 Use POST /api/listeners to start listening to events");

        // Initialize metrics
        eventHandler.init();

        // Note: Listeners are now started via REST API, not automatically
    }

    void onStop(@Observes ShutdownEvent event) {
        LOG.info("🛑 Stopping all active listeners");
        listenerManager.stopAll();
    }
}
