package io.github.rigazilla.memory.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {

    @JsonProperty("event")
    private String event;

    @JsonProperty("kind")
    private String kind;

    @JsonProperty("data")
    private JsonNode data;

    @JsonProperty("cursor")
    private String cursor;

    public Event() {
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    public boolean isConversationEvent() {
        return "conversation".equals(kind);
    }

    public boolean isEntryEvent() {
        return "entry".equals(kind);
    }

    public boolean isResponseEvent() {
        return "response".equals(kind);
    }

    public boolean isStreamEvent() {
        return "stream".equals(kind);
    }

    @Override
    public String toString() {
        return "Event{" +
                "event='" + event + '\'' +
                ", kind='" + kind + '\'' +
                ", data=" + data +
                ", cursor='" + cursor + '\'' +
                '}';
    }
}
