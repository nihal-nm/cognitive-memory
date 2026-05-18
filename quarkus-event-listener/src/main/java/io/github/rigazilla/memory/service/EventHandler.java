package io.github.rigazilla.memory.service;

import io.github.rigazilla.memory.model.Event;
import io.github.rigazilla.memory.model.EventContext;

/**
 * Interface for handling memory-service events.
 * Implement this interface to process events in a custom way.
 */
public interface EventHandler {

    /**
     * Called when a new event is received from the stream.
     *
     * @param event the received event
     */
    void onEvent(Event event);

    /**
     * Called when a new event is received from the stream with auth context.
     *
     * @param eventContext the event with authentication context
     */
    default void onEvent(EventContext eventContext) {
        onEvent(eventContext.getEvent());
    }

    /**
     * Called when the connection is opened and the stream starts.
     */
    void onOpen();

    /**
     * Called when an error occurs during streaming.
     *
     * @param throwable the error that occurred
     */
    void onError(Throwable throwable);

    /**
     * Called when the connection is closed.
     */
    void onClosed();
}
