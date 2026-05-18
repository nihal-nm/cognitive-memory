package io.github.rigazilla.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rigazilla.memory.config.MemoryServiceConfig;
import io.github.rigazilla.memory.model.Event;
import io.github.rigazilla.memory.model.EventContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * SSE client for memory-service event stream.
 */
@ApplicationScoped
public class MemoryServiceEventClient {

    private static final Logger LOG = Logger.getLogger(MemoryServiceEventClient.class);

    @Inject
    MemoryServiceConfig config;

    @Inject
    EventHandler eventHandler;

    @Inject
    ObjectMapper objectMapper;

    private OkHttpClient httpClient;
    private EventSource eventSource;
    private volatile boolean connected = false;

    public void start() {
        if (!config.enabled()) {
            LOG.warn("Memory service event listener is disabled");
            return;
        }

        LOG.infof("Connecting to memory-service event stream: %s", buildUrl());

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // No timeout for SSE
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        Request request = new Request.Builder()
                .url(buildUrl())
                .addHeader("Accept", "text/event-stream")
                .addHeader("Authorization", "Bearer " + config.token())
                .addHeader("X-API-Key", config.apiKey())
                .build();

        EventSourceListener sseListener = new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                connected = true;
                LOG.info("SSE connection opened");
                eventHandler.onOpen();
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                try {
                    Event event = objectMapper.readValue(data, Event.class);
                    LOG.debugf("Received event: %s", event);

                    // Wrap event with auth context from config
                    EventContext eventContext = new EventContext(event, config.token(), config.apiKey());
                    eventHandler.onEvent(eventContext);
                } catch (IOException e) {
                    LOG.errorf(e, "Failed to parse event data: %s", data);
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                connected = false;
                LOG.info("SSE connection closed");
                eventHandler.onClosed();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                connected = false;
                if (response != null) {
                    LOG.errorf(t, "SSE connection failed with status: %d", response.code());
                } else {
                    LOG.error("SSE connection failed", t);
                }
                eventHandler.onError(t);
            }
        };

        eventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, sseListener);
    }

    public void stop() {
        if (eventSource != null) {
            LOG.info("Closing SSE connection");
            eventSource.cancel();
            eventSource = null;
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
        connected = false;
    }

    public boolean isConnected() {
        return connected;
    }

    private String buildUrl() {
        return String.format("%s/v1/events?kinds=%s&detail=%s",
                config.baseUrl(),
                config.eventKinds(),
                config.detailLevel());
    }
}
