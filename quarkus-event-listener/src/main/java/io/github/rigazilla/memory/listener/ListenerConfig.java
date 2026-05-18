package io.github.rigazilla.memory.listener;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ListenerConfig {

    @JsonProperty("token")
    private String token;

    @JsonProperty("conversationId")
    private String conversationId;

    @JsonProperty("apiKey")
    private String apiKey;

    public ListenerConfig() {
    }

    public ListenerConfig(String token, String conversationId, String apiKey) {
        this.token = token;
        this.conversationId = conversationId;
        this.apiKey = apiKey;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
