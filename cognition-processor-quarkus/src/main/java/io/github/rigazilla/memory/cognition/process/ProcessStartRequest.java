package io.github.rigazilla.memory.cognition.process;

import java.util.List;

/**
 * Optional request body for {@code POST /api/processes/{id}/start}.
 *
 * <p>All fields are optional. Processes that do not recognise a field silently ignore it.
 * Sending no body at all is equivalent to sending {@code {}}.
 *
 * <p>Example — scope enrichment to one user:
 * <pre>{@code
 * { "namespacePrefix": ["user", "caroline"] }
 * }</pre>
 *
 * <p>Example — scope to a fully-qualified leaf namespace (skips namespace discovery):
 * <pre>{@code
 * { "namespacePrefix": ["user", "caroline", "cognition.v1", "episodic"] }
 * }</pre>
 */
public record ProcessStartRequest(
        List<String> namespacePrefix
) {
    /** Canonical empty instance — equivalent to sending no body. */
    public static final ProcessStartRequest EMPTY = new ProcessStartRequest(null);
}
