package io.github.rigazilla.memory.topics;

import java.time.Instant;
import java.util.List;

/**
 * Represents detected topics for a conversation.
 */
public class ConversationTopics {

    private String conversationId;
    private String title;
    private List<String> topics;
    private Instant detectedAt;
    private int messageCount;

    public ConversationTopics() {
    }

    public ConversationTopics(String conversationId, String title, List<String> topics, int messageCount) {
        this.conversationId = conversationId;
        this.title = title;
        this.topics = topics;
        this.detectedAt = Instant.now();
        this.messageCount = messageCount;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    @Override
    public String toString() {
        return "ConversationTopics{" +
                "conversationId='" + conversationId + '\'' +
                ", title='" + title + '\'' +
                ", topics=" + topics +
                ", messageCount=" + messageCount +
                ", detectedAt=" + detectedAt +
                '}';
    }
}
