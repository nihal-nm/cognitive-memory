package io.github.rigazilla.memory.listener;

import com.fasterxml.jackson.annotation.JsonProperty;
import okhttp3.sse.EventSource;

import java.time.Instant;

public class ListenerInfo {

    @JsonProperty("id")
    private final String id;

    @JsonProperty("token")
    private final String token;

    @JsonProperty("conversationId")
    private final String conversationId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("startedAt")
    private final Instant startedAt;

    private transient EventSource eventSource;

    public ListenerInfo(String id, String token, String conversationId) {
        this.id = id;
        this.token = token;
        this.conversationId = conversationId;
        this.status = "starting";
        this.startedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public EventSource getEventSource() {
        return eventSource;
    }

    public void setEventSource(EventSource eventSource) {
        this.eventSource = eventSource;
    }
}
