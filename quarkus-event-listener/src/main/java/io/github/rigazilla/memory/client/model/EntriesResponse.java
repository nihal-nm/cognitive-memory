package io.github.rigazilla.memory.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EntriesResponse {

    @JsonProperty("data")
    private List<Entry> data;

    @JsonProperty("afterCursor")
    private String afterCursor;

    public EntriesResponse() {
    }

    public List<Entry> getData() {
        return data;
    }

    public void setData(List<Entry> data) {
        this.data = data;
    }

    public String getAfterCursor() {
        return afterCursor;
    }

    public void setAfterCursor(String afterCursor) {
        this.afterCursor = afterCursor;
    }
}
