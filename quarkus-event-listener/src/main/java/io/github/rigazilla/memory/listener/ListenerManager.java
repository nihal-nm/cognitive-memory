package io.github.rigazilla.memory.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rigazilla.memory.config.MemoryServiceConfig;
import io.github.rigazilla.memory.model.Event;
import io.github.rigazilla.memory.model.EventContext;
import io.github.rigazilla.memory.service.EventHandler;
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
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ListenerManager {

    private static final Logger LOG = Logger.getLogger(ListenerManager.class);

    @Inject
    MemoryServiceConfig config;

    @Inject
    EventHandler eventHandler;

    @Inject
    ObjectMapper objectMapper;

    private final Map<String, ListenerInfo> activeListeners = new ConcurrentHashMap<>();
    private OkHttpClient httpClient;

    public ListenerManager() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // No timeout for SSE
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public ListenerInfo startListener(ListenerConfig listenerConfig) {
        String listenerId = UUID.randomUUID().toString();
        String token = listenerConfig.getToken();
        String conversationId = listenerConfig.getConversationId();
        String apiKey = listenerConfig.getApiKey() != null ? listenerConfig.getApiKey() : config.apiKey();

        ListenerInfo listenerInfo = new ListenerInfo(listenerId, token, conversationId);
        activeListeners.put(listenerId, listenerInfo);

        String url = buildUrl(conversationId);
        LOG.infof("🚀 Starting listener %s for token=%s, conversationId=%s, url=%s",
                listenerId, token, conversationId, url);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "text/event-stream")
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("X-API-Key", apiKey)
                .build();

        EventSourceListener sseListener = new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                listenerInfo.setStatus("connected");
                LOG.infof("Listener %s connected", listenerId);
                eventHandler.onOpen();
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                try {
                    Event event = objectMapper.readValue(data, Event.class);
                    LOG.debugf("Listener %s received event: %s", listenerId, event);

                    // Wrap event with auth context from this listener
                    EventContext eventContext = new EventContext(event, token, apiKey);
                    eventHandler.onEvent(eventContext);
                } catch (IOException e) {
                    LOG.errorf(e, "Listener %s failed to parse event data: %s", listenerId, data);
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                listenerInfo.setStatus("closed");
                LOG.infof("Listener %s closed", listenerId);
                eventHandler.onClosed();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                listenerInfo.setStatus("failed");
                if (response != null) {
                    LOG.errorf(t, "Listener %s failed with status: %d", listenerId, response.code());
                } else {
                    LOG.errorf(t, "Listener %s failed", listenerId);
                }
                eventHandler.onError(t);
            }
        };

        EventSource eventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, sseListener);
        listenerInfo.setEventSource(eventSource);

        return listenerInfo;
    }

    public boolean stopListener(String listenerId) {
        ListenerInfo listenerInfo = activeListeners.remove(listenerId);
        if (listenerInfo == null) {
            LOG.warnf("Listener %s not found", listenerId);
            return false;
        }

        LOG.infof("🛑 Stopping listener %s", listenerId);
        EventSource eventSource = listenerInfo.getEventSource();
        if (eventSource != null) {
            eventSource.cancel();
        }
        listenerInfo.setStatus("stopped");
        return true;
    }

    public ListenerInfo getListener(String listenerId) {
        return activeListeners.get(listenerId);
    }

    public Collection<ListenerInfo> getAllListeners() {
        return activeListeners.values();
    }

    public void stopAll() {
        LOG.info("🛑 Stopping all listeners");
        activeListeners.keySet().forEach(this::stopListener);
    }

    private String buildUrl(String conversationId) {
        StringBuilder url = new StringBuilder();
        url.append(config.baseUrl()).append("/v1/events");
        url.append("?kinds=").append(config.eventKinds());
        url.append("&detail=").append(config.detailLevel());

        // Add conversation filter if specified
        if (conversationId != null && !conversationId.isEmpty()) {
            url.append("&conversation=").append(conversationId);
        }

        return url.toString();
    }
}
