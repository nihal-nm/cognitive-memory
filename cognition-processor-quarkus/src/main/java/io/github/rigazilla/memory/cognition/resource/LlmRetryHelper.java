package io.github.rigazilla.memory.cognition.resource;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.function.Supplier;

/**
 * Shared helper that executes a supplier with exponential-backoff retry on
 * transient LLM failures (timeouts, 429 / 503 responses).
 *
 * <p>The retry policy is driven by three config properties so every deployment
 * context can tune behaviour without recompilation:
 * <ul>
 *   <li>{@code cognition.llm.retry.max-attempts} — maximum number of attempts
 *       (1 = no retry, 3 = two retries after the first failure).</li>
 *   <li>{@code cognition.llm.retry.initial-delay-ms} — wait before the first
 *       retry; doubles on each subsequent attempt.</li>
 *   <li>{@code cognition.llm.retry.max-delay-ms} — upper bound for the delay
 *       so the backoff does not grow without limit.</li>
 * </ul>
 *
 * <p>Any exception that escapes all attempts is re-thrown as-is so callers
 * can apply their own error handling (count errors, log, skip item, etc.).
 */
@ApplicationScoped
public class LlmRetryHelper {

    private static final Logger LOG = Logger.getLogger(LlmRetryHelper.class);

    @ConfigProperty(name = "cognition.llm.retry.max-attempts", defaultValue = "3")
    int maxAttempts;

    @ConfigProperty(name = "cognition.llm.retry.initial-delay-ms", defaultValue = "1000")
    long initialDelayMs;

    @ConfigProperty(name = "cognition.llm.retry.max-delay-ms", defaultValue = "30000")
    long maxDelayMs;

    /**
     * Create a pre-configured instance for use in unit tests (no CDI required).
     * Not for production use.
     */
    public static LlmRetryHelper forTesting(int maxAttempts, long initialDelayMs, long maxDelayMs) {
        LlmRetryHelper h = new LlmRetryHelper();
        h.maxAttempts = maxAttempts;
        h.initialDelayMs = initialDelayMs;
        h.maxDelayMs = maxDelayMs;
        return h;
    }

    /**
     * Execute {@code action} with retry and exponential backoff.
     *
     * @param <T>         return type of the LLM call
     * @param description short human-readable label for log messages
     * @param action      the LLM call to execute
     * @return the result of {@code action} on success
     * @throws RuntimeException the last exception if all attempts fail
     */
    public <T> T withRetry(String description, Supplier<T> action) {
        int attempts = maxAttempts < 1 ? 1 : maxAttempts;
        long delayMs = initialDelayMs;
        Exception lastException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                lastException = e;
                if (attempt < attempts) {
                    LOG.warnf("LLM call failed [%s] attempt %d/%d: %s — retrying in %dms",
                            description, attempt, attempts, e.getMessage(), delayMs);
                    sleep(delayMs);
                    delayMs = Math.min(delayMs * 2, maxDelayMs);
                } else {
                    LOG.errorf(e, "LLM call failed [%s] after %d attempt(s), giving up",
                            description, attempts);
                }
            }
        }

        // All attempts exhausted — re-throw the last exception.
        if (lastException instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException("LLM call [" + description + "] failed after " + attempts + " attempts",
                lastException);
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
