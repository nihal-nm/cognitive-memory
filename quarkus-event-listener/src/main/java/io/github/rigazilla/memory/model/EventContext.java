package io.github.rigazilla.memory.model;

/**
 * Wrapper that carries an event along with its authentication context.
 * This allows proper authorization when fetching additional data.
 */
public class EventContext {
    private final Event event;
    private final String token;
    private final String apiKey;

    public EventContext(Event event, String token, String apiKey) {
        this.event = event;
        this.token = token;
        this.apiKey = apiKey;
    }

    public Event getEvent() {
        return event;
    }

    public String getToken() {
        return token;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getAuthorizationHeader() {
        return "Bearer " + token;
    }
}
